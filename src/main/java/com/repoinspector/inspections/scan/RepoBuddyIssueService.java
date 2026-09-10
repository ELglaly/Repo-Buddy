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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

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
    private final Object scanLock = new Object();
    private final Set<VirtualFile> dirtyFiles = ConcurrentHashMap.newKeySet();
    private final AtomicLong requestedGeneration = new AtomicLong();
    private CompletableFuture<List<Finding>> pendingProjectScan;
    private boolean fullScanRequested;
    private boolean smartDrainScheduled;
    private boolean workerActive;

    public RepoBuddyIssueService(@NotNull Project project) {
        this.project = project;
        this.refreshQueue = new MergingUpdateQueue(
                "RepoBuddyIssues", 700, true, null, this);
        installEditTrigger();
    }

    public static @NotNull RepoBuddyIssueService getInstance(@NotNull Project project) {
        return project.getService(RepoBuddyIssueService.class);
    }


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


    /** Re-scans the whole project and replaces the entire cache. */
    public void refreshProject() {
        requestProjectScan();
    }

    /** Runs or joins the current full-project scan and completes after the shared cache is updated. */
    public @NotNull CompletableFuture<List<Finding>> requestProjectScan() {
        synchronized (scanLock) {
            if (pendingProjectScan != null && !pendingProjectScan.isDone()) return pendingProjectScan;
            CompletableFuture<List<Finding>> future = new CompletableFuture<>();
            pendingProjectScan = future;
            if (project.isDisposed()) {
                future.completeExceptionally(new IllegalStateException("Project is disposed"));
                return future;
            }
            requestedGeneration.incrementAndGet();
            fullScanRequested = true;
            scheduleSmartDrain();
            return future;
        }
    }

    /** Re-scans a single file and replaces only that file's cache entry. */
    public void refreshFile(@Nullable VirtualFile file) {
        if (file == null || !file.isValid()) return;
        dirtyFiles.add(file);
        synchronized (scanLock) { scheduleSmartDrain(); }
    }

    /** Re-scans every file currently open in an editor (used after a settings change). */
    public void refreshOpenFiles() {
        for (VirtualFile vf : FileEditorManager.getInstance(project).getOpenFiles()) {
            refreshFile(vf);
        }
    }

    /** One smart-mode callback and one worker serialize full and incremental scans. */
    private void scheduleSmartDrain() {
        if (smartDrainScheduled || project.isDisposed()) return;
        smartDrainScheduled = true;
        DumbService.getInstance(project).runWhenSmart(() ->
                ApplicationManager.getApplication().executeOnPooledThread(this::drain));
    }

    private void drain() {
        final boolean full;
        final long generation;
        final List<VirtualFile> files;
        final CompletableFuture<List<Finding>> projectFuture;
        synchronized (scanLock) {
            smartDrainScheduled = false;
            if (project.isDisposed() || workerActive) return;
            full = fullScanRequested;
            if (!full && dirtyFiles.isEmpty()) return;
            workerActive = true;
            generation = requestedGeneration.get();
            projectFuture = full ? pendingProjectScan : null;
            if (full) {
                fullScanRequested = false;
                files = List.of();
            } else {
                files = List.copyOf(dirtyFiles);
                dirtyFiles.removeAll(files);
            }
        }
        try {
            if (project.isDisposed()) throw new IllegalStateException("Project is disposed");
            if (full) {
                List<Finding> findings = scanner.scan(project, GlobalSearchScope.projectScope(project));
                publishFull(generation, findings, projectFuture);
            } else {
                Map<VirtualFile, List<Finding>> findings = new ConcurrentHashMap<>();
                for (VirtualFile file : files) {
                    if (project.isDisposed()) break;
                    findings.put(file, scanner.scanVirtualFile(project, file));
                }
                publishIncremental(findings);
            }
        } catch (RuntimeException error) {
            if (projectFuture != null) projectFuture.completeExceptionally(error);
            if (full) releaseFullWorker();
        } finally {
            synchronized (scanLock) {
                if (!full) {
                    workerActive = false;
                    if (!dirtyFiles.isEmpty() || fullScanRequested) scheduleSmartDrain();
                }
            }
        }
    }

    private void publishFull(long generation, @NotNull List<Finding> findings,
                             @Nullable CompletableFuture<List<Finding>> future) {
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                if (project.isDisposed() || generation != requestedGeneration.get()) {
                    if (future != null) future.cancel(false);
                    return;
                }
                applyResult(findings, true, null);
                if (future != null) completeOffEdt(future, findings);
                synchronized (scanLock) {
                    if (pendingProjectScan == future) pendingProjectScan = null;
                }
            } finally {
                // The dirty follow-up must start only after the full replacement is visible;
                // otherwise an older full result could overwrite the newer file result.
                releaseFullWorker();
            }
        }, project.getDisposed());
    }

    private void releaseFullWorker() {
        synchronized (scanLock) {
            workerActive = false;
            if (!project.isDisposed() && (!dirtyFiles.isEmpty() || fullScanRequested)) scheduleSmartDrain();
        }
    }

    private void publishIncremental(@NotNull Map<VirtualFile, List<Finding>> findings) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;
            applyIncrementalResults(findings);
        }, project.getDisposed());
    }

    /** Futures are deliberately completed outside the EDT so client continuations stay off it. */
    private static void completeOffEdt(@NotNull CompletableFuture<List<Finding>> future,
                                       @NotNull List<Finding> findings) {
        ApplicationManager.getApplication().executeOnPooledThread(() -> future.complete(List.copyOf(findings)));
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

    /** Applies an incremental batch atomically from the UI's perspective and notifies once. */
    private void applyIncrementalResults(@NotNull Map<VirtualFile, List<Finding>> results) {
        for (Map.Entry<VirtualFile, List<Finding>> entry : results.entrySet()) {
            VirtualFile file = entry.getKey();
            List<Finding> findings = entry.getValue();
            if (findings.isEmpty()) byFile.remove(file.getPath());
            else byFile.put(file.getPath(), List.copyOf(findings));
        }
        if (!results.isEmpty()) fireUpdated();
    }

    private void fireUpdated() {
        project.getMessageBus().syncPublisher(RepoBuddyIssueListener.TOPIC).issuesUpdated();
        EditorNotifications.getInstance(project).updateAllNotifications();
    }


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
        dirtyFiles.clear();
        synchronized (scanLock) {
            if (pendingProjectScan != null) pendingProjectScan.cancel(true);
        }
        Disposer.dispose(refreshQueue);
    }
}
