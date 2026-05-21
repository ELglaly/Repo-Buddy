package com.repoinspector.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentManager;
import org.jetbrains.annotations.NotNull;

/** Small helpers for activating the RepoBuddy tool window and its tabs from indicators. */
public final class RepoBuddyToolWindows {

    /** Tool-window id as registered in plugin.xml. */
    public static final String TOOL_WINDOW_ID = "RepoBuddy";

    /** Display name of the Issues tab content. */
    public static final String ISSUES_TAB = "Issues";

    private RepoBuddyToolWindows() {}

    /** Opens the RepoBuddy tool window and selects the Issues tab. No-op if the window is absent. */
    public static void openIssuesTab(@NotNull Project project) {
        ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID);
        if (toolWindow == null) return;
        toolWindow.activate(() -> {
            ContentManager contentManager = toolWindow.getContentManager();
            Content issues = contentManager.findContent(ISSUES_TAB);
            if (issues != null) contentManager.setSelectedContent(issues);
        }, true);
    }
}
