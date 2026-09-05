package com.repoinspector.runner.startup;

import com.intellij.execution.configurations.JavaParameters;
import com.intellij.execution.configurations.ParametersList;
import com.intellij.execution.configurations.RunProfile;
import com.intellij.execution.Executor;
import com.intellij.execution.runners.JavaProgramPatcher;
import com.intellij.openapi.diagnostic.Logger;
import com.repoinspector.settings.RepoBuddySettings;
import org.jetbrains.annotations.NotNull;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

/** Adds RepoBuddy's agent to the in-memory Java command line immediately before launch. */
public final class RepoBuddyJavaProgramPatcher extends JavaProgramPatcher {
    private static final Logger LOG = Logger.getInstance(RepoBuddyJavaProgramPatcher.class);
    @Override public void patchJavaParameters(@NotNull Executor executor, @NotNull RunProfile configuration, @NotNull JavaParameters javaParameters) {
        if (!RepoBuddySettings.getInstance().isJavaAgentEnabled()) { LOG.debug("RepoBuddy: Java agent injection disabled"); return; }
        Path agent = RepoBuddyAgentPathResolver.resolve();
        if (agent == null || !Files.isRegularFile(agent)) { LOG.warn("RepoBuddy: Java agent is unavailable; launching without instrumentation"); return; }
        ParametersList parameters = javaParameters.getVMParametersList();
        ArrayList<String> existing = new ArrayList<>(parameters.getParameters());
        if (existing.removeIf(RepoBuddyAgentArguments::isRepoBuddyAgentArgument)) LOG.info("RepoBuddy: removed existing Java agent argument before runtime injection");
        parameters.clearAll(); parameters.addAll(existing); parameters.add("-javaagent:" + agent.toAbsolutePath());
        LOG.debug("RepoBuddy: injected Java agent at runtime for " + configuration.getName());
    }
}
