package com.repoinspector.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.openapi.wm.StatusBarWidgetFactory;
import org.jetbrains.annotations.NotNull;

/** Registers the {@link RepoBuddyStatusBarWidget} in the editor status bar. */
public final class RepoBuddyStatusBarWidgetFactory implements StatusBarWidgetFactory {

    public static final String ID = "RepoBuddy.IssuesCount";

    @Override
    public @NotNull String getId() {
        return ID;
    }

    @Override
    public @NotNull String getDisplayName() {
        return "RepoBuddy Issues";
    }

    @Override
    public @NotNull StatusBarWidget createWidget(@NotNull Project project) {
        return new RepoBuddyStatusBarWidget(project);
    }
}
