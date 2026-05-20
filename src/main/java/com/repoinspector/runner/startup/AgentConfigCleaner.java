package com.repoinspector.runner.startup;

import com.intellij.execution.CommonJavaRunConfigurationParameters;
import com.intellij.execution.RunManager;
import com.intellij.ide.plugins.DynamicPluginListener;
import com.intellij.ide.plugins.IdeaPluginDescriptor;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import org.jetbrains.annotations.NotNull;

/**
 * Removes the {@code -javaagent:…repoBuddy-agent…} JVM flag from all open-project
 * run configurations when the RepoBuddy plugin is uninstalled or dynamically unloaded.
 *
 * <p>Registered as an application-level {@link DynamicPluginListener} in {@code plugin.xml}.
 */
public final class AgentConfigCleaner implements DynamicPluginListener {

    private static final String PLUGIN_ID    = "com.elglaly.repobuddy";
    private static final String AGENT_MARKER = "repoBuddy-agent";

    @Override
    public void beforePluginUnload(@NotNull IdeaPluginDescriptor descriptor,
                                   boolean isUpdate) {
        if (!PLUGIN_ID.equals(descriptor.getPluginId().getIdString())) return;

        ApplicationManager.getApplication().invokeLater(() -> {
            for (Project project : ProjectManager.getInstance().getOpenProjects()) {
                if (project.isDisposed()) continue;
                removeAgentFromConfigurations(project);
            }
        });
    }

    private static void removeAgentFromConfigurations(Project project) {
        RunManager mgr = RunManager.getInstance(project);
        for (var settings : mgr.getAllSettings()) {
            if (!(settings.getConfiguration() instanceof CommonJavaRunConfigurationParameters cfg)) continue;
            String current = cfg.getVMParameters();
            if (current == null || !current.contains(AGENT_MARKER)) continue;
            String cleaned = current
                    .replaceAll("-javaagent:\\S*" + AGENT_MARKER + "\\S*", "")
                    .replaceAll("\\s{2,}", " ")
                    .trim();
            cfg.setVMParameters(cleaned.isEmpty() ? null : cleaned);
        }
    }
}
