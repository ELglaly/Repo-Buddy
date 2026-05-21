package com.repoinspector.ui;

import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerEvent;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.vfs.VirtualFile;
import com.repoinspector.inspections.scan.RepoBuddyIssueService;
import org.jetbrains.annotations.NotNull;

/**
 * Keeps the per-file issue cache warm for the file the user is looking at: re-scans on open and
 * on selection change so the editor banner and status-bar counter reflect the active file.
 * Registered as a project listener in plugin.xml.
 */
public final class RepoBuddyEditorSelectionListener implements FileEditorManagerListener {

    @Override
    public void fileOpened(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
        RepoBuddyIssueService.getInstance(source.getProject()).refreshFile(file);
    }

    @Override
    public void selectionChanged(@NotNull FileEditorManagerEvent event) {
        VirtualFile file = event.getNewFile();
        if (file != null) {
            RepoBuddyIssueService.getInstance(event.getManager().getProject()).refreshFile(file);
        }
    }
}
