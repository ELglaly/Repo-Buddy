package com.repoinspector.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.repoinspector.integration.RepoBuddyCliManager;
import com.repoinspector.integration.RepoBuddyIntegrationUi;
import org.jetbrains.annotations.NotNull;

public final class ConfigureCodexAction extends AnAction implements DumbAware {
    @Override public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        if (project != null) RepoBuddyIntegrationUi.configure(project, RepoBuddyCliManager.AiClient.CODEX, null);
    }
    @Override public void update(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        event.getPresentation().setEnabled(project != null && !project.isDisposed() && project.getBasePath() != null);
    }
}
