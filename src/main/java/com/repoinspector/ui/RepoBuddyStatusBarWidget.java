package com.repoinspector.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.util.Consumer;
import com.repoinspector.inspections.scan.RepoBuddyIssueListener;
import com.repoinspector.inspections.scan.RepoBuddyIssueService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.Component;
import java.awt.event.MouseEvent;

/**
 * Status-bar counter showing how many RepoBuddy issues are in the file currently open in the
 * editor. Reads from {@link RepoBuddyIssueService}'s cache and refreshes when the service fires
 * {@link RepoBuddyIssueListener#TOPIC}. Clicking opens the Issues tab.
 */
public final class RepoBuddyStatusBarWidget implements StatusBarWidget, StatusBarWidget.TextPresentation {

    private final Project project;
    private StatusBar statusBar;

    public RepoBuddyStatusBarWidget(@NotNull Project project) {
        this.project = project;
        project.getMessageBus().connect(this).subscribe(RepoBuddyIssueListener.TOPIC,
                (RepoBuddyIssueListener) () -> {
                    if (statusBar != null) statusBar.updateWidget(ID());
                });
    }

    @Override
    public @NotNull String ID() {
        return RepoBuddyStatusBarWidgetFactory.ID;
    }

    @Override
    public void install(@NotNull StatusBar statusBar) {
        this.statusBar = statusBar;
    }

    @Override
    public @Nullable WidgetPresentation getPresentation() {
        return this;
    }

    @Override
    public void dispose() {
        statusBar = null;
    }

    // ── TextPresentation ─────────────────────────────────────────────────────

    @Override
    public @NotNull String getText() {
        RepoBuddyIssueService service = RepoBuddyIssueService.getInstance(project);
        VirtualFile current = service.selectedFile();
        int count = service.countForFile(current);
        return "RepoBuddy: " + count;
    }

    @Override
    public float getAlignment() {
        return Component.LEFT_ALIGNMENT;
    }

    @Override
    public @NotNull String getTooltipText() {
        return "RepoBuddy issues in the current file — click to open the Issues panel";
    }

    @Override
    public @NotNull Consumer<MouseEvent> getClickConsumer() {
        return event -> RepoBuddyToolWindows.openIssuesTab(project);
    }
}
