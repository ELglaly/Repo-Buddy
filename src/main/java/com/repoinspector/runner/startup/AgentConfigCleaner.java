package com.repoinspector.runner.startup;

import com.intellij.execution.CommonJavaRunConfigurationParameters;
import com.intellij.execution.RunManager;
import com.intellij.ide.plugins.DynamicPluginListener;
import com.intellij.ide.plugins.IdeaPluginDescriptor;
import com.intellij.openapi.diagnostic.Logger;
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
    private static final Logger LOG = Logger.getInstance(AgentConfigCleaner.class);

    @Override
    public void beforePluginUnload(@NotNull IdeaPluginDescriptor descriptor,
                                   boolean isUpdate) {
        if (!PLUGIN_ID.equals(descriptor.getPluginId().getIdString())) return;

        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
            if (project.isDisposed()) continue;
            try { removeAgentFromConfigurations(project); }
            catch (Exception e) { LOG.warn("RepoBuddy: failed to clean legacy Java agent entries during plugin unload", e); }
        }
    }

    public static int removeAgentFromConfigurations(Project project) {
        RunManager mgr = RunManager.getInstance(project);
        int removed = 0;
        for (var settings : mgr.getAllSettings()) {
            if (!(settings.getConfiguration() instanceof CommonJavaRunConfigurationParameters cfg)) continue;
            String current = cfg.getVMParameters();
            String cleaned = RepoBuddyAgentArguments.removeRepoBuddyAgentArguments(current);
            if (java.util.Objects.equals(current, cleaned)) continue;
            cfg.setVMParameters(cleaned);
            removed++;
        }
        return removed;
    }
}
