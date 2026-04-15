package com.repoinspector.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

/**
 * Creates the "Repo Buddy" tool window with two tabs:
 * <ol>
 *   <li>"Repository Usage" — call-count table ({@link RepoInspectorPanel})</li>
 *   <li>"Call Chain Tracer" — per-endpoint repository tracer ({@link CallChainPanel})</li>
 * </ol>
 */
public class RepoInspectorToolWindowFactory implements ToolWindowFactory {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        ContentFactory contentFactory = ContentFactory.getInstance();

        // Tab 1: existing repository usage table
        RepoInspectorPanel usagePanel = new RepoInspectorPanel(project);
        Content usageContent = contentFactory.createContent(usagePanel, "Repository Usage", false);
        toolWindow.getContentManager().addContent(usageContent);

        // Tab 2: call-chain tracer
        CallChainPanel chainPanel = new CallChainPanel(project);
        Content chainContent = contentFactory.createContent(chainPanel, "Call Chain Tracer", false);
        toolWindow.getContentManager().addContent(chainContent);

    }
}
