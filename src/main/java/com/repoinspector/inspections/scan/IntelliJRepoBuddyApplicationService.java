package com.repoinspector.inspections.scan;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.roots.ProjectRootManager;
import com.repoinspector.core.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** IntelliJ-backed implementation shared by the CLI and MCP adapters. */
@Service(Service.Level.PROJECT)
public final class IntelliJRepoBuddyApplicationService implements RepoBuddyApplicationService {
    private static final int MAX_SNAPSHOTS = 10;
    private static final int MAX_CONTEXT_BYTES = 32 * 1024;
    private final Project project;
    private final LinkedHashMap<String, Snapshot> snapshots = new LinkedHashMap<>();
    private volatile String latestScanId;

    private record Snapshot(String id, Instant createdAt, AnalysisScope scope, ChangeAvailability availability,
                            String message, int preExisting, int resolved, List<RepoBuddyIssue> issues) {}

    public IntelliJRepoBuddyApplicationService(Project project) { this.project = project; }

    public static IntelliJRepoBuddyApplicationService getInstance(Project project) {
        return project.getService(IntelliJRepoBuddyApplicationService.class);
    }

    @Override
    public CompletionStage<RepoBuddyScanResult> scan(ScanRequest request) {
        if (project.isDisposed()) return CompletableFuture.failedFuture(new RepoBuddyException(
                RepoBuddyErrorCode.REPOBUDDY_PROJECT_DISPOSED, "Project is disposed"));
        if (DumbService.isDumb(project)) return CompletableFuture.failedFuture(new RepoBuddyException(
                RepoBuddyErrorCode.REPOBUDDY_PROJECT_INDEXING, "Project indexes are not ready; retry after indexing completes"));
        long started = System.nanoTime();
        return RepoBuddyIssueService.getInstance(project).requestProjectScan().thenApply(findings -> {
            List<RepoBuddyIssue> selected = normalize(findings).stream()
                    .filter(issue -> request.severity() == null || issue.severity() == request.severity())
                    .filter(issue -> request.ruleId() == null || issue.ruleId().equalsIgnoreCase(request.ruleId()))
                    .toList();
            String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            store(new Snapshot(id, Instant.now(), AnalysisScope.ALL, ChangeAvailability.AVAILABLE,
                    "All RepoBuddy issues", 0, 0, selected));
            return new RepoBuddyScanResult("1", id, ScanStatus.COMPLETED,
                    (System.nanoTime() - started) / 1_000_000L, Instant.now(),
                    new RepoBuddyScanResult.ProjectRef(projectId(), project.getName(), root().toString()),
                    IssueFilters.summarize(selected));
        });
    }

    @Override
    public CompletionStage<IssuePage> listIssues(IssueQuery query) {
        try {
            Snapshot snapshot = snapshot(query.scanId());
            return CompletableFuture.completedFuture(new IssuePage("1", snapshot.id(),
                    IssueFilters.count(snapshot.issues(), query), query.limit(), query.offset(),
                    IssueFilters.filter(snapshot.issues(), query)));
        } catch (RuntimeException error) { return CompletableFuture.failedFuture(error); }
    }

    @Override
    public CompletionStage<RepoBuddyIssue> getIssue(String issueId) {
        try { return CompletableFuture.completedFuture(findIssue(issueId)); }
        catch (RuntimeException error) { return CompletableFuture.failedFuture(error); }
    }

    @Override
    public CompletionStage<RepoBuddyIssueContext> getIssueContext(String issueId, int contextLines) {
        try { return CompletableFuture.completedFuture(context(findIssue(issueId), contextLines)); }
        catch (RuntimeException error) { return CompletableFuture.failedFuture(error); }
    }

    @Override
    public CompletionStage<List<RepoBuddyRule>> getRules(RuleQuery query) {
        List<RepoBuddyRule> rules = RepoBuddyRules.all().stream()
                .filter(rule -> query.enabled() == null || rule.enabled() == query.enabled())
                .filter(rule -> query.ruleId() == null || rule.id().equalsIgnoreCase(query.ruleId()))
                .toList();
        return CompletableFuture.completedFuture(rules);
    }

    @Override
    public CompletionStage<RepoBuddyProjectInfo> getProjectInfo() {
        Path root = root();
        String build = Files.exists(root.resolve("pom.xml")) ? "MAVEN"
                : Files.exists(root.resolve("build.gradle.kts")) || Files.exists(root.resolve("build.gradle")) ? "GRADLE" : "UNKNOWN";
        String joined = readBuildFiles(root).toLowerCase(Locale.ROOT);
        boolean boot = joined.contains("spring-boot");
        boolean jpa = joined.contains("spring-data-jpa") || joined.contains("spring-boot-starter-data-jpa");
        List<String> frameworks = new ArrayList<>();
        if (boot) frameworks.add("Spring Boot");
        if (jpa) frameworks.add("Spring Data JPA");
        return CompletableFuture.completedFuture(new RepoBuddyProjectInfo("1", projectId(), project.getName(),
                root.toString(), build, ProjectRootManager.getInstance(project).getProjectSdk() == null ? null
                        : ProjectRootManager.getInstance(project).getProjectSdk().getVersionString(),
                boot, jpa, frameworks, RepoBuddyRules.all().stream().map(RepoBuddyRule::id).toList(), RepoBuddyVersion.current(), true));
    }

    @Override
    public CompletionStage<ScopedIssuePage> getIssues(ScopedIssueRequest request) {
        if (request.scanId() != null) {
            try { return CompletableFuture.completedFuture(page(snapshot(request.scanId()), request)); }
            catch (RuntimeException error) { return CompletableFuture.failedFuture(error); }
        }
        if (request.scope() == AnalysisScope.CHANGED) {
            return RepoBuddyChangeAnalysisService.getInstance(project).requestScan(null).thenApply(result -> {
                String id = newScanId();
                List<RepoBuddyIssue> issues = filter(result.introducedIssues(), request.severity(), request.minimumSeverity(), request.ruleId(), request.file());
                Snapshot snapshot = new Snapshot(id, Instant.now(), AnalysisScope.CHANGED, result.availability(), result.message(),
                        result.preExistingCount(), result.resolvedCount(), issues);
                store(snapshot);
                return page(snapshot, request);
            });
        }
        return RepoBuddyIssueService.getInstance(project).requestProjectScan().thenCompose(findings ->
                RepoBuddyChangeAnalysisService.getInstance(project).requestScan(null).thenApply(changes -> {
            String id = newScanId();
            List<RepoBuddyIssue> issues = filter(classifyAll(normalize(findings), changes), request.severity(), request.minimumSeverity(), request.ruleId(), request.file());
            Snapshot snapshot = new Snapshot(id, Instant.now(), AnalysisScope.ALL, changes.availability(),
                    "All RepoBuddy issues", changes.preExistingCount(), changes.resolvedCount(), issues);
            store(snapshot);
            return page(snapshot, request);
        }));
    }

    @Override
    public CompletionStage<ChangeCheckResult> checkChanges(Severity severity, String ruleId) {
        if (ruleId != null && RepoBuddyRules.byId(ruleId) == null)
            return CompletableFuture.failedFuture(new IllegalArgumentException("Unknown RepoBuddy rule: " + ruleId));
        return RepoBuddyChangeAnalysisService.getInstance(project).requestScan(null).thenApply(result -> {
            List<RepoBuddyIssue> selected = filter(result.introducedIssues(), severity, null, ruleId, null);
            String id = newScanId();
            Snapshot snapshot = new Snapshot(id, Instant.now(), AnalysisScope.CHANGED, result.availability(), result.message(),
                    result.preExistingCount(), result.resolvedCount(), selected);
            store(snapshot);
            List<RepoBuddyIssue> first = selected.stream().limit(25).toList();
            boolean available = result.availability() == ChangeAvailability.AVAILABLE || result.availability() == ChangeAvailability.NO_CHANGES;
            return new ChangeCheckResult("1", id, available, available && selected.isEmpty(), "HEAD", result.availability(),
                    result.message(), selected.size(), result.preExistingCount(), result.resolvedCount(), selected.size() > 25, first);
        });
    }

    private ScopedIssuePage page(Snapshot snapshot, ScopedIssueRequest request) {
        int from = Math.min(request.offset(), snapshot.issues().size());
        int to = Math.min(from + request.limit(), snapshot.issues().size());
        List<RepoBuddyIssue> page = snapshot.issues().subList(from, to);
        return new ScopedIssuePage("1", snapshot.id(), snapshot.scope(), "HEAD", snapshot.availability(), snapshot.message(),
                snapshot.issues().size(), snapshot.preExisting(), snapshot.resolved(), request.limit(), request.offset(),
                to < snapshot.issues().size() ? to : null, to < snapshot.issues().size(), page);
    }

    private static List<RepoBuddyIssue> filter(List<RepoBuddyIssue> issues, Severity severity, Severity minimum,
                                                String rule, String file) {
        return issues.stream().filter(i -> severity == null || i.severity() == severity)
                .filter(i -> minimum == null || i.severity().atLeast(minimum))
                .filter(i -> rule == null || i.ruleId().equalsIgnoreCase(rule))
                .filter(i -> file == null || IssueIds.normalizePath(i.path()).equalsIgnoreCase(IssueIds.normalizePath(file)))
                .toList();
    }

    private static List<RepoBuddyIssue> classifyAll(List<RepoBuddyIssue> all, RepoBuddyChangeAnalysisService.Result changes) {
        if (changes.availability() != ChangeAvailability.AVAILABLE && changes.availability() != ChangeAvailability.NO_CHANGES)
            return all;
        Map<String, RepoBuddyIssue> introduced = changes.introducedIssues().stream()
                .collect(java.util.stream.Collectors.toMap(RepoBuddyIssue::id, issue -> issue, (a, b) -> a));
        return all.stream().map(issue -> {
            RepoBuddyIssue changed = introduced.get(issue.id());
            if (changed != null) return changed;
            return new RepoBuddyIssue(issue.id(), issue.ruleId(), issue.category(), issue.severity(), issue.path(), issue.line(),
                    issue.column(), issue.message(), issue.explanation(), issue.suggestedFix(), issue.affectedSymbol(),
                    IssueChangeStatus.PRE_EXISTING, null);
        }).toList();
    }

    private static String newScanId() { return UUID.randomUUID().toString().replace("-", "").substring(0, 12); }

    public String projectId() {
        RepoBuddyRule marker = new RepoBuddyRule("project", "PROJECT", "project", "PROJECT",
                Severity.INFO, true, "", "", List.of());
        String digest = IssueIds.create(marker, root().toString(), project.getName(), "", 0);
        return project.getName().replaceAll("[^A-Za-z0-9._-]", "-") + "-" + digest.substring(digest.length() - 8);
    }

    private List<RepoBuddyIssue> normalize(List<RepoBuddyInspectionScanner.Finding> findings) {
        Path root = root();
        List<RepoBuddyIssue> result = new ArrayList<>();
        for (var finding : findings) {
            RepoBuddyRule rule = RepoBuddyRules.byId(finding.ruleId());
            if (rule == null) continue;
            Path file = Path.of(finding.filePath()).toAbsolutePath().normalize();
            if (!file.startsWith(root)) continue;
            String relative = IssueIds.normalizePath(root.relativize(file).toString());
            result.add(new RepoBuddyIssue(IssueIds.create(rule, relative, finding.stableAnchor(), finding.message(), finding.offset()),
                    rule.id(), rule.category(), rule.defaultSeverity(), relative, finding.line(), finding.column(),
                    finding.message(), rule.description(), rule.recommendation(), finding.affectedSymbol(), null, null));
        }
        result.sort(Comparator.comparing(RepoBuddyIssue::path, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(issue -> issue.line() == null ? 0 : issue.line()));
        return List.copyOf(result);
    }

    private RepoBuddyIssue findIssue(String issueId) {
        return snapshot(null).issues().stream().filter(issue -> issue.id().equals(issueId)).findFirst()
                .orElseThrow(() -> new RepoBuddyException(RepoBuddyErrorCode.REPOBUDDY_ISSUE_NOT_FOUND,
                        "RepoBuddy issue not found: " + issueId));
    }

    private RepoBuddyIssueContext context(RepoBuddyIssue issue, int lines) {
        SourceContextReader.Result result = SourceContextReader.read(root(), issue.path(),
                issue.line() == null ? 1 : issue.line(), lines, 50, MAX_CONTEXT_BYTES);
        return new RepoBuddyIssueContext("1", new RepoBuddyIssueContext.IssueRef(issue.id(), issue.path(), issue.line()),
                new RepoBuddyIssueContext.Context(result.startLine(), result.endLine(), result.code(), result.truncated()));
    }

    private void store(Snapshot snapshot) {
        synchronized (snapshots) {
            snapshots.entrySet().removeIf(entry -> entry.getValue().createdAt().isBefore(Instant.now().minusSeconds(1800)));
            snapshots.put(snapshot.id(), snapshot);
            while (snapshots.size() > MAX_SNAPSHOTS) snapshots.remove(snapshots.keySet().iterator().next());
            latestScanId = snapshot.id();
        }
    }

    private Snapshot snapshot(String requested) {
        synchronized (snapshots) {
            String id = requested == null ? latestScanId : requested;
            Snapshot value = id == null ? null : snapshots.get(id);
            if (value == null) throw new RepoBuddyException(RepoBuddyErrorCode.REPOBUDDY_SCAN_NOT_FOUND,
                    "No matching scan is available; run 'repobuddy check' first");
            return value;
        }
    }

    private Path root() {
        if (project.isDisposed()) throw new RepoBuddyException(RepoBuddyErrorCode.REPOBUDDY_PROJECT_DISPOSED, "Project is disposed");
        String base = project.getBasePath();
        if (base == null) throw new RepoBuddyException(RepoBuddyErrorCode.REPOBUDDY_PROJECT_NOT_FOUND, "Project has no local root");
        return Path.of(base).toAbsolutePath().normalize();
    }

    private static String readBuildFiles(Path root) {
        StringBuilder value = new StringBuilder();
        for (String name : List.of("pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts")) {
            Path file = root.resolve(name);
            if (!Files.isRegularFile(file)) continue;
            try { value.append(Files.readString(file)); } catch (IOException ignored) { }
        }
        return value.toString();
    }
}
