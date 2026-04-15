package com.repoinspector.runner.startup;

import com.intellij.execution.CommonJavaRunConfigurationParameters;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Runs once per project open.  Injects {@code -javaagent:/tmp/repoBuddy-agent.jar}
 * into every Java / Spring Boot run configuration so the agent starts automatically
 * the next time the user launches their app from IntelliJ.
 *
 * <p>The agent JAR is bundled inside the plugin JAR as a classpath resource
 * ({@code /agent/repoBuddy-agent.jar}).  This class extracts it to the system temp
 * directory on first use so the path is always valid regardless of where IntelliJ
 * installed the plugin.
 */
public final class AgentRunConfigPatcher implements StartupActivity, DumbAware {

    /** Resource path inside the plugin JAR. */
    private static final String AGENT_RESOURCE = "/agent/repoBuddy-agent.jar";
    /** Stable file name used in the temp directory and in the -javaagent flag. */
    private static final String AGENT_JAR_NAME = "repoBuddy-agent.jar";
    /** Substring used to detect / update an existing agent flag. */
    private static final String AGENT_MARKER   = "repoBuddy-agent";

    @Override
    public void runActivity(@NotNull Project project) {
        Path agentJar = extractAgentJar();
        if (agentJar == null) {
            notify(project,
                    "RepoBuddy: agent JAR not found",
                    "Could not find the embedded agent JAR. "
                            + "Please reinstall the RepoBuddy plugin.",
                    NotificationType.WARNING);
            return;
        }

        // Normalise to forward slashes — works on all JVMs including Windows.
        String jvmFlag = "-javaagent:" + agentJar.toAbsolutePath().toString().replace('\\', '/');

        ApplicationManager.getApplication().invokeLater(
                () -> patchConfigurations(project, jvmFlag));
    }

    // -------------------------------------------------------------------------

    private static void patchConfigurations(Project project, String jvmFlag) {
        RunManager runManager = RunManager.getInstance(project);
        int patched = 0;

        for (RunnerAndConfigurationSettings settings : runManager.getAllSettings()) {
            if (!(settings.getConfiguration() instanceof CommonJavaRunConfigurationParameters cfg)) continue;

            String current = cfg.getVMParameters();

            if (current != null && current.contains(AGENT_MARKER)) {
                // Already present — update the path in case the plugin was reinstalled.
                String updated = current
                        .replaceAll("-javaagent:\\S*" + AGENT_MARKER + "\\S*", jvmFlag)
                        .trim();
                if (!updated.equals(current)) {
                    cfg.setVMParameters(updated);
                    patched++;
                }
            } else {
                cfg.setVMParameters(
                        current == null || current.isBlank() ? jvmFlag : jvmFlag + " " + current);
                patched++;
            }
        }

        if (patched > 0) {
            notify(project,
                    "RepoBuddy agent configured",
                    "Added to " + patched + " run configuration(s). "
                            + "Restart your Spring Boot app to activate.",
                    NotificationType.INFORMATION);
        }
    }

    /**
     * Extracts the agent JAR from the plugin's classpath resources to the system
     * temp directory and returns its path.  Returns {@code null} on any failure.
     *
     * <p>Falls back to the agent module's Gradle build output when the plugin is
     * being run directly from the IDE (i.e. the plugin JAR has not been assembled yet
     * and the resource is therefore absent from the classpath).
     */
    @Nullable
    private static Path extractAgentJar() {
        try (InputStream in = openAgentJarStream()) {
            if (in == null) return null;
            Path dest = Path.of(System.getProperty("java.io.tmpdir"), AGENT_JAR_NAME);
            Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
            return dest;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Opens the agent JAR as a stream, first from the plugin classpath resource,
     * then from the agent module's Gradle build output (development-mode fallback).
     */
    @Nullable
    private static InputStream openAgentJarStream() {
        InputStream in = AgentRunConfigPatcher.class.getResourceAsStream(AGENT_RESOURCE);
        if (in != null) return in;
        // Fallback: when running via "Run Plugin" from the IDE without a full Gradle build,
        // the plugin JAR hasn't been assembled and the resource isn't on the classpath.
        // Walk up from the class location to find agent/build/libs/repoBuddy-agent-*.jar.
        try {
            Path location = Path.of(
                    AgentRunConfigPatcher.class.getProtectionDomain()
                            .getCodeSource().getLocation().toURI());
            Path dir = Files.isDirectory(location) ? location : location.getParent();
            for (int i = 0; i < 6 && dir != null; i++, dir = dir.getParent()) {
                Path libs = dir.resolve("agent/build/libs");
                if (!Files.isDirectory(libs)) continue;
                try (var entries = Files.list(libs)) {
                    java.util.Optional<Path> jar = entries
                            .filter(p -> p.getFileName().toString().startsWith(AGENT_MARKER)
                                      && p.getFileName().toString().endsWith(".jar"))
                            .findFirst();
                    if (jar.isPresent()) return Files.newInputStream(jar.get());
                }
            }
        } catch (Exception e) {
            // Could not locate the agent JAR in the build output; caller will show a warning.
        }
        return null;
    }

    private static void notify(Project project, String title, String content,
                                NotificationType type) {
        NotificationGroupManager.getInstance()
                .getNotificationGroup("RepoBuddy Notifications")
                .createNotification(title, content, type)
                .notify(project);
    }
}
