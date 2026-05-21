package com.repoinspector.ui;

import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.EditorNotificationPanel;
import com.intellij.ui.EditorNotificationProvider;
import com.intellij.ui.EditorNotifications;
import com.repoinspector.inspections.scan.RepoBuddyIssueService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Full-width banner shown at the top of a file that has RepoBuddy findings: "RepoBuddy: N issues
 * in this file" with an action that opens the Issues tab. The count is read from
 * {@link RepoBuddyIssueService}'s cache (no scanning happens here); the service triggers a refresh
 * of all banners whenever that cache changes.
 *
 * <p>The banner can be dismissed per file; it reappears once the file's issue count changes.
 */
public final class RepoBuddyEditorBanner implements EditorNotificationProvider {

    /** Files dismissed by the user, remembered with the count at dismissal time. */
    private final Map<String, Integer> dismissedAtCount = new ConcurrentHashMap<>();

    @Override
    public @Nullable Function<? super FileEditor, ? extends JComponent> collectNotificationData(
            @NotNull Project project, @NotNull VirtualFile file) {

        int count = RepoBuddyIssueService.getInstance(project).countForFile(file);
        if (count <= 0) return null;

        Integer dismissed = dismissedAtCount.get(file.getPath());
        if (dismissed != null && dismissed == count) return null;

        return fileEditor -> buildPanel(project, file, count);
    }

    private JComponent buildPanel(@NotNull Project project, @NotNull VirtualFile file, int count) {
        EditorNotificationPanel panel = new EditorNotificationPanel(EditorNotificationPanel.Status.Warning);
        panel.setText("RepoBuddy: " + count + (count == 1 ? " issue" : " issues") + " in this file");
        panel.createActionLabel("Open Issues", () -> RepoBuddyToolWindows.openIssuesTab(project));
        panel.createActionLabel("Dismiss", () -> {
            dismissedAtCount.put(file.getPath(), count);
            EditorNotifications.getInstance(project).updateAllNotifications();
        });
        return panel;
    }
}
