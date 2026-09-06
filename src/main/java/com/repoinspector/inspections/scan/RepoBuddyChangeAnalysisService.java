package com.repoinspector.inspections.scan;

import com.intellij.diff.comparison.ComparisonManager;
import com.intellij.diff.comparison.ComparisonPolicy;
import com.intellij.diff.comparison.DiffTooBigException;
import com.intellij.diff.fragments.LineFragment;
import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.vcsUtil.VcsUtil;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ChangeListManager;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.PsiManager;
import com.repoinspector.core.*;
import git4idea.GitContentRevision;
import git4idea.GitRevisionNumber;
import git4idea.index.GitFileStatus;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Computes the semantic RepoBuddy issue delta between Git HEAD and the current working tree. */
@Service(Service.Level.PROJECT)
public final class RepoBuddyChangeAnalysisService {
    public record Result(ChangeAvailability availability, String message, String baseline,
                         List<ChangedFile> changedFiles,
                         List<RepoBuddyInspectionScanner.Finding> introducedFindings,
                         List<RepoBuddyIssue> introducedIssues, int preExistingCount, int resolvedCount) {
        public Result { changedFiles = List.copyOf(changedFiles); introducedFindings = List.copyOf(introducedFindings); introducedIssues = List.copyOf(introducedIssues); }
    }

    private record Candidate(Path beforePath, Path currentPath, @Nullable ContentRevision beforeRevision) {}
    private record Snapshot(String path, String baselineText, String currentText, List<ChangedRange> ranges,
                            RepoBuddyInspectionScanner.Finding currentNavigation) {}
    private record BaselineKey(String head, String path, int contentHash) {}

    private final Project project;
    private final RepoBuddyInspectionScanner scanner = new RepoBuddyInspectionScanner();
    private final Map<BaselineKey, List<RepoBuddyInspectionScanner.Finding>> baselineCache = new ConcurrentHashMap<>();
    private volatile Result latest = new Result(ChangeAvailability.NO_CHANGES, "No changed files", "HEAD", List.of(), List.of(), List.of(), 0, 0);

    public RepoBuddyChangeAnalysisService(Project project) { this.project = project; }
    public static RepoBuddyChangeAnalysisService getInstance(Project project) { return project.getService(RepoBuddyChangeAnalysisService.class); }
    public Result latest() { return latest; }

    public CompletableFuture<Result> requestScan(@Nullable VirtualFile onlyFile) {
        if (project.isDisposed()) return CompletableFuture.failedFuture(new IllegalStateException("Project is disposed"));
        CompletableFuture<Result> future = new CompletableFuture<>();
        ChangeListManager.getInstance(project).invokeAfterUpdate(false, () ->
                DumbService.getInstance(project).runWhenSmart(() -> ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                Result result = analyze(onlyFile);
                latest = result;
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (!project.isDisposed()) project.getMessageBus().syncPublisher(RepoBuddyIssueListener.TOPIC).issuesUpdated();
                }, project.getDisposed());
                future.complete(result);
            } catch (com.intellij.openapi.progress.ProcessCanceledException canceled) {
                future.cancel(false);
            } catch (RuntimeException error) {
                Result failed = unavailable(ChangeAvailability.FAILED, "Unable to compare current changes with Git HEAD: " +
                        (error.getMessage() == null ? "analysis failed" : error.getMessage()));
                future.complete(failed);
            }
        })));
        return future;
    }

    private Result analyze(@Nullable VirtualFile onlyFile) {
        List<GitRepository> repositories = GitRepositoryManager.getInstance(project).getRepositories();
        if (repositories.isEmpty()) return unavailable(ChangeAvailability.NOT_GIT, "Changed Issues requires a Git repository");
        if (repositories.stream().anyMatch(repo -> repo.getInfo().getCurrentRevision() == null))
            return unavailable(ChangeAvailability.NO_HEAD, "Git HEAD is unavailable");

        Map<Path, Candidate> candidates = collectCandidates(repositories);
        if (onlyFile != null) candidates.entrySet().removeIf(e -> !samePath(e.getValue().currentPath(), onlyFile) && !samePath(e.getValue().beforePath(), onlyFile));
        candidates.entrySet().removeIf(e -> !isJava(e.getValue()));
        if (candidates.isEmpty()) return unavailable(ChangeAvailability.NO_CHANGES,
                onlyFile == null ? "No changed Java files" : "The current file has no Git changes");

        List<RepoBuddyInspectionScanner.Finding> introducedFindings = new ArrayList<>();
        List<RepoBuddyIssue> introducedIssues = new ArrayList<>();
        List<ChangedFile> changedFiles = new ArrayList<>();
        int preExisting = 0;
        int resolved = 0;
        Path root = Path.of(Objects.requireNonNull(project.getBasePath())).toAbsolutePath().normalize();

        for (Candidate candidate : candidates.values()) {
            ProgressManager.checkCanceled();
            String beforeText = baselineText(candidate, repositories);
            String currentText = currentText(candidate.currentPath());
            String logicalPath = relative(root, candidate.currentPath() != null ? candidate.currentPath() : candidate.beforePath());
            List<ChangedRange> ranges = ranges(beforeText, currentText);
            GitRepository repository = repositoryFor(candidate.beforePath() != null ? candidate.beforePath() : candidate.currentPath(), repositories);
            String head = repository == null ? "HEAD" : repository.getInfo().getCurrentRevision();
            changedFiles.add(changedFile(root, candidate, logicalPath, head, ranges));
            List<RepoBuddyInspectionScanner.Finding> baseline = beforeText == null ? List.of()
                    : baselineFindings(candidate, repositories, beforeText, logicalPath);
            List<RepoBuddyInspectionScanner.Finding> current = currentText == null ? List.of()
                    : currentFindings(candidate.currentPath(), logicalPath);

            IssueDelta.Result delta = IssueDelta.compare(
                    baseline.stream().map(f -> fingerprint(logicalPath, f)).toList(),
                    current.stream().map(f -> fingerprint(logicalPath, f)).toList());
            for (int index = 0; index < current.size(); index++) {
                var finding = current.get(index);
                if (delta.currentStatuses().get(index) == IssueChangeStatus.PRE_EXISTING) { preExisting++; continue; }
                ChangeAttribution attribution = attribution(logicalPath, finding, ranges);
                introducedFindings.add(finding);
                introducedIssues.add(toIssue(root, finding, attribution));
            }
            resolved += delta.resolvedCount();
        }
        introducedFindings.sort(Comparator.comparing(RepoBuddyInspectionScanner.Finding::filePath, String.CASE_INSENSITIVE_ORDER)
                .thenComparingInt(RepoBuddyInspectionScanner.Finding::line));
        introducedIssues.sort(Comparator.comparing(RepoBuddyIssue::path, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(issue -> issue.line() == null ? 0 : issue.line()));
        return new Result(ChangeAvailability.AVAILABLE, introducedIssues.size() + " issues introduced by your current changes",
                "HEAD", changedFiles, introducedFindings, introducedIssues, preExisting, resolved);
    }

    private Map<Path, Candidate> collectCandidates(List<GitRepository> repositories) {
        Map<Path, Candidate> result = new LinkedHashMap<>();
        ChangeListManager manager = ChangeListManager.getInstance(project);
        for (Change change : manager.getAllChanges()) {
            Path before = path(change.getBeforeRevision());
            Path after = path(change.getAfterRevision());
            Path key = normalize(after != null ? after : before);
            if (key != null) result.put(key, new Candidate(before, after, change.getBeforeRevision()));
        }
        for (FilePath path : manager.getUnversionedFilesPaths()) {
            Path value = normalize(path.getIOFile().toPath());
            if (value != null) result.putIfAbsent(value, new Candidate(null, value, null));
        }
        for (GitRepository repository : repositories) {
            for (GitFileStatus status : repository.getStagingAreaHolder().getAllRecords()) {
                Path after = normalize(status.getPath().getIOFile().toPath());
                boolean added = status.isUntracked() || status.getIndex() == 'A';
                Path before = added ? null : normalize(status.getOrigPath() == null ? after : status.getOrigPath().getIOFile().toPath());
                if (after != null) result.putIfAbsent(after, new Candidate(before, Files.exists(after) ? after : null, null));
            }
        }
        return result;
    }

    private @Nullable String baselineText(Candidate candidate, List<GitRepository> repositories) {
        try {
            if (candidate.beforeRevision() != null) {
                String content = candidate.beforeRevision().getContent();
                if (content == null) throw new IllegalStateException("No HEAD content for " + candidate.beforePath().getFileName());
                return content;
            }
            if (candidate.beforePath() == null) return null;
            GitRepository repo = repositoryFor(candidate.beforePath(), repositories);
            if (repo == null) return null;
            String head = repo.getInfo().getCurrentRevision();
            ContentRevision revision = GitContentRevision.createRevision(
                    VcsUtil.getFilePath(candidate.beforePath().toFile(), false), new GitRevisionNumber(head), project);
            if (revision == null) throw new IllegalStateException("No HEAD revision for " + candidate.beforePath().getFileName());
            String content = revision.getContent();
            if (content == null) throw new IllegalStateException("No HEAD content for " + candidate.beforePath().getFileName());
            return content;
        } catch (VcsException error) {
            if (candidate.beforePath() == null) return null;
            throw new IllegalStateException("HEAD content is unavailable for " + candidate.beforePath().getFileName(), error);
        }
    }

    private List<RepoBuddyInspectionScanner.Finding> baselineFindings(Candidate candidate, List<GitRepository> repositories,
                                                                       String text, String logicalPath) {
        GitRepository repo = repositoryFor(candidate.beforePath(), repositories);
        String head = repo == null ? "HEAD" : repo.getInfo().getCurrentRevision();
        BaselineKey key = new BaselineKey(head, logicalPath, text.hashCode());
        if (baselineCache.size() >= 256 && !baselineCache.containsKey(key)) baselineCache.clear();
        return baselineCache.computeIfAbsent(key, ignored -> ReadAction.compute(() -> {
            String name = candidate.beforePath() == null ? Path.of(logicalPath).getFileName().toString() : candidate.beforePath().getFileName().toString();
            PsiFile psi = PsiFileFactory.getInstance(project).createFileFromText(name, JavaFileType.INSTANCE, text);
            return remap(scanner.scanFile(project, psi), logicalPath, null);
        }));
    }

    private List<RepoBuddyInspectionScanner.Finding> currentFindings(Path path, String logicalPath) {
        VirtualFile file = LocalFileSystem.getInstance().findFileByNioFile(path);
        if (file == null) return List.of();
        PsiFile psi = ReadAction.compute(() -> PsiManager.getInstance(project).findFile(file));
        return psi == null ? List.of() : remap(scanner.scanFile(project, psi), logicalPath, file);
    }

    private static List<RepoBuddyInspectionScanner.Finding> remap(List<RepoBuddyInspectionScanner.Finding> source,
                                                                  String path, @Nullable VirtualFile navigation) {
        return source.stream().map(f -> new RepoBuddyInspectionScanner.Finding(f.ruleId(), f.inspection(), f.fileName(), path,
                f.line(), f.column(), f.message(), f.stableAnchor(), f.enclosingClass(), f.enclosingMethod(),
                f.affectedSymbol(), f.symbolStartLine(), f.symbolEndLine(), navigation, f.offset())).toList();
    }

    private @Nullable String currentText(@Nullable Path path) {
        if (path == null || !Files.exists(path)) return null;
        VirtualFile file = LocalFileSystem.getInstance().findFileByNioFile(path);
        if (file == null) return null;
        return ReadAction.compute(() -> {
            var document = FileDocumentManager.getInstance().getDocument(file);
            if (document != null) return document.getText();
            try { return VfsUtilCore.loadText(file); }
            catch (java.io.IOException error) { throw new IllegalStateException("Unable to read changed file", error); }
        });
    }

    private static List<ChangedRange> ranges(@Nullable String before, @Nullable String after) {
        if (before == null && after == null) return List.of();
        if (before == null) return List.of(new ChangedRange("ADDED", null, null, 1, lineCount(after)));
        if (after == null) return List.of(new ChangedRange("DELETED", 1, lineCount(before), null, null));
        try {
            List<ChangedRange> result = new ArrayList<>();
            for (LineFragment f : ComparisonManager.getInstance().compareLines(before, after, ComparisonPolicy.DEFAULT, new EmptyProgressIndicator())) {
                String kind = f.getStartLine1() == f.getEndLine1() ? "ADDED" : f.getStartLine2() == f.getEndLine2() ? "DELETED" : "MODIFIED";
                result.add(new ChangedRange(kind, inclusiveStart(f.getStartLine1(), f.getEndLine1()), inclusiveEnd(f.getStartLine1(), f.getEndLine1()),
                        inclusiveStart(f.getStartLine2(), f.getEndLine2()), inclusiveEnd(f.getStartLine2(), f.getEndLine2())));
            }
            return List.copyOf(result);
        } catch (DiffTooBigException error) {
            return List.of(new ChangedRange("MODIFIED", 1, lineCount(before), 1, lineCount(after)));
        }
    }

    private static ChangeAttribution attribution(String path, RepoBuddyInspectionScanner.Finding finding, List<ChangedRange> ranges) {
        List<ChangedRange> direct = ranges.stream().filter(r -> contains(r, finding.line())).toList();
        if (!direct.isEmpty()) return new ChangeAttribution(IssueChangeStatus.INTRODUCED, path, true, direct,
                finding.affectedSymbol(), false, ChangeAttributionReason.ISSUE_ON_CHANGED_CODE);
        List<ChangedRange> symbol = ranges.stream().filter(r -> overlaps(r, finding.symbolStartLine(), finding.symbolEndLine())).toList();
        return new ChangeAttribution(IssueChangeStatus.INTRODUCED, path, false, symbol,
                finding.affectedSymbol(), false, symbol.isEmpty() ? ChangeAttributionReason.NEW_ISSUE_IN_CHANGED_FILE
                        : ChangeAttributionReason.ISSUE_IN_CHANGED_SYMBOL);
    }

    private RepoBuddyIssue toIssue(Path root, RepoBuddyInspectionScanner.Finding finding, ChangeAttribution attribution) {
        RepoBuddyRule rule = RepoBuddyRules.byId(finding.ruleId());
        String relative = finding.filePath();
        return new RepoBuddyIssue(IssueIds.create(rule, relative, finding.stableAnchor(), finding.message(), 0),
                rule.id(), rule.category(), rule.defaultSeverity(), relative, finding.line(), finding.column(), finding.message(),
                rule.description(), rule.recommendation(), finding.affectedSymbol(), IssueChangeStatus.INTRODUCED, attribution);
    }

    private Result unavailable(ChangeAvailability availability, String message) {
        Result result = new Result(availability, message, "HEAD", List.of(), List.of(), List.of(), 0, 0);
        latest = result;
        return result;
    }

    private static String fingerprint(String path, RepoBuddyInspectionScanner.Finding finding) {
        return String.join("\u001f", finding.ruleId(), IssueIds.normalizePath(path), finding.enclosingClass(),
                finding.enclosingMethod(), finding.affectedSymbol(), finding.message().replaceAll("\\s+", " ").trim());
    }
    private static ChangedFile changedFile(Path root, Candidate candidate, String logicalPath, String head, List<ChangedRange> ranges) {
        GitChangeType type = candidate.beforePath() == null ? GitChangeType.ADDED
                : candidate.currentPath() == null ? GitChangeType.DELETED
                : !candidate.beforePath().equals(candidate.currentPath()) ? GitChangeType.MOVED : GitChangeType.MODIFIED;
        String beforePath = candidate.beforePath() == null ? null : relative(root, candidate.beforePath());
        return new ChangedFile(logicalPath, beforePath, type, head, "WORKTREE", ranges,
                ranges.stream().filter(r -> r.kind().equals("ADDED")).toList(),
                ranges.stream().filter(r -> r.kind().equals("MODIFIED")).toList(),
                ranges.stream().filter(r -> r.kind().equals("DELETED")).toList());
    }
    private static @Nullable Path path(@Nullable ContentRevision revision) { return revision == null ? null : normalize(revision.getFile().getIOFile().toPath()); }
    private static @Nullable Path normalize(@Nullable Path path) { return path == null ? null : path.toAbsolutePath().normalize(); }
    private static boolean samePath(@Nullable Path path, VirtualFile file) { return path != null && path.equals(Path.of(file.getPath()).toAbsolutePath().normalize()); }
    private static boolean isJava(Candidate candidate) { Path p = candidate.currentPath() != null ? candidate.currentPath() : candidate.beforePath(); return p != null && p.toString().toLowerCase(Locale.ROOT).endsWith(".java"); }
    private static String relative(Path root, Path path) { Path value = path.toAbsolutePath().normalize(); return IssueIds.normalizePath(value.startsWith(root) ? root.relativize(value).toString() : value.getFileName().toString()); }
    private static @Nullable GitRepository repositoryFor(@Nullable Path path, List<GitRepository> repos) { if (path == null) return null; return repos.stream().filter(r -> path.startsWith(Path.of(r.getRoot().getPath()).toAbsolutePath().normalize())).findFirst().orElse(null); }
    private static int lineCount(@Nullable String text) { return text == null || text.isEmpty() ? 1 : (int) text.lines().count(); }
    private static Integer inclusiveStart(int start, int end) { return start == end ? null : start + 1; }
    private static Integer inclusiveEnd(int start, int end) { return start == end ? null : end; }
    private static boolean contains(ChangedRange range, int line) { return range.afterStartLine() != null && line >= range.afterStartLine() && line <= range.afterEndLine(); }
    private static boolean overlaps(ChangedRange range, int start, int end) { return range.afterStartLine() != null && range.afterStartLine() <= end && range.afterEndLine() >= start; }
}
