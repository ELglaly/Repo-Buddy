package com.repoinspector.inspections.scan;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiTreeChangeAdapter;
import com.intellij.psi.PsiTreeChangeEvent;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.ui.EditorNotifications;
import com.intellij.util.ui.update.MergingUpdateQueue;
import com.intellij.util.ui.update.Update;
import com.repoinspector.inspections.scan.RepoBuddyInspectionScanner.Finding;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single source of truth for RepoBuddy inspection findings within a project.
 *
 * <p>Runs {@link RepoBuddyInspectionScanner} off the EDT (in smart mode) and caches the results
 * per file. The Issues panel, the editor banner, and the status-bar widget all read from this
 * cache instead of scanning independently, so they never diverge and a file is never scanned
 * three times. Cache mutations publish {@link RepoBuddyIssueListener#TOPIC} and refresh the
 * editor banners on the EDT.
 */
@Service(Service.Level.PROJECT)
public final class RepoBuddyIssueService implements Disposable {

    private final Project project;
    private final RepoBuddyInspectionScanner scanner = new RepoBuddyInspectionScanner();

    /** Keyed by {@link VirtualFile#getPath()}; read off-EDT by the banner, so kept thread-safe. */
    private final Map<String, List<Finding>> byFile = new ConcurrentHashMap<>();

    private final MergingUpdateQueue refreshQueue;

    public RepoBuddyIssueService(@NotNull Project project) {
        this.project = project;
        this.refreshQueue = new MergingUpdateQueue(
                "RepoBuddyIssues", 700, true, null, this);
        installEditTrigger();
    }

    public static @NotNull RepoBuddyIssueService getInstance(@NotNull Project project) {
        return project.getService(RepoBuddyIssueService.class);
    }

    // =========================================================================
    // Queries (read from cache — cheap, callable from any thread)
    // =========================================================================

    /** Cached findings for one file, or an empty list if it has not been scanned. */
    public @NotNull List<Finding> findingsForFile(@Nullable VirtualFile file) {
        if (file == null) return List.of();
        return byFile.getOrDefault(file.getPath(), List.of());
    }

    /** Cached findings for one file, count only. */
    public int countForFile(@Nullable VirtualFile file) {
        return findingsForFile(file).size();
    }

    /** All cached findings across every scanned file, ordered by path then line. */
    public @NotNull List<Finding> findings() {
        List<Finding> all = new ArrayList<>();
        for (List<Finding> perFile : byFile.values()) all.addAll(perFile);
        all.sort((a, b) -> {
            int byPath = a.filePath().compareToIgnoreCase(b.filePath());
            return byPath != 0 ? byPath : Integer.compare(a.line(), b.line());
        });
        return all;
    }

    public int totalCount() {
        int total = 0;
        for (List<Finding> perFile : byFile.values()) total += perFile.size();
        return total;
    }

    // =========================================================================
    // Refresh (async, off-EDT)
    // =========================================================================

    /** Re-scans the whole project and replaces the entire cache. */
    public void refreshProject() {
        runScan(() -> scanner.scan(project, GlobalSearchScope.projectScope(project)), true, null);
    }

    /** Re-scans a single file and replaces only that file's cache entry. */
    public void refreshFile(@Nullable VirtualFile file) {
        if (file == null || !file.isValid()) return;
        runScan(() -> {
            PsiFile psiFile = ReadAction.compute(() -> {
                PsiFile f = PsiManager.getInstance(project).findFile(file);
                return (f != null && f.isValid()) ? f : null;
            });
            return psiFile == null ? List.<Finding>of() : scanner.scanFile(project, psiFile);
        }, false, file);
    }

    /** Re-scans every file currently open in an editor (used after a settings change). */
    public void refreshOpenFiles() {
        for (VirtualFile vf : FileEditorManager.getInstance(project).getOpenFiles()) {
            refreshFile(vf);
        }
    }

    private void runScan(@NotNull java.util.function.Supplier<List<Finding>> task,
                         boolean replaceAll, @Nullable VirtualFile singleFile) {
        DumbService.getInstance(project).runWhenSmart(() ->
                ApplicationManager.getApplication().executeOnPooledThread(() -> {
                    List<Finding> result = task.get();
                    ApplicationManager.getApplication().invokeLater(
                            () -> applyResult(result, replaceAll, singleFile),
                            project.getDisposed());
                }));
    }

    private void applyResult(@NotNull List<Finding> result, boolean replaceAll,
                             @Nullable VirtualFile singleFile) {
        if (replaceAll) {
            byFile.clear();
            for (Finding f : result) {
                byFile.computeIfAbsent(f.filePath(), k -> new ArrayList<>()).add(f);
            }
        } else if (singleFile != null) {
            if (result.isEmpty()) byFile.remove(singleFile.getPath());
            else byFile.put(singleFile.getPath(), List.copyOf(result));
        }
        fireUpdated();
    }

    private void fireUpdated() {
        project.getMessageBus().syncPublisher(RepoBuddyIssueListener.TOPIC).issuesUpdated();
        EditorNotifications.getInstance(project).updateAllNotifications();
    }

    // =========================================================================
    // Live-edit trigger: debounced re-scan of an edited Java file
    // =========================================================================

    private void installEditTrigger() {
        PsiManager.getInstance(project).addPsiTreeChangeListener(new PsiTreeChangeAdapter() {
            @Override public void childrenChanged(@NotNull PsiTreeChangeEvent event) { onPsiChange(event); }
            @Override public void childAdded(@NotNull PsiTreeChangeEvent event)      { onPsiChange(event); }
            @Override public void childRemoved(@NotNull PsiTreeChangeEvent event)    { onPsiChange(event); }
            @Override public void childReplaced(@NotNull PsiTreeChangeEvent event)   { onPsiChange(event); }
        }, this);
    }

    private void onPsiChange(@NotNull PsiTreeChangeEvent event) {
        PsiFile psiFile = event.getFile();
        if (psiFile == null) return;
        VirtualFile vf = psiFile.getVirtualFile();
        if (vf == null || !"java".equalsIgnoreCase(vf.getExtension())) return;
        refreshQueue.queue(Update.create(vf.getPath(), () -> refreshFile(vf)));
    }

    /** Resolves the file currently open in the active editor, if any. */
    public @Nullable VirtualFile selectedFile() {
        var editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor == null) return null;
        return FileDocumentManager.getInstance().getFile(editor.getDocument());
    }

    @Override
    public void dispose() {
        byFile.clear();
        Disposer.dispose(refreshQueue);
    }
}
