package com.repoinspector.runner.startup;

import com.intellij.execution.CommonJavaRunConfigurationParameters;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Runs once per project open. Injects {@code -javaagent:<tmp>/repoBuddy-agent-<hash>.jar}
 * into every Java / Spring Boot run configuration so the agent starts automatically
 * the next time the user launches their app from IntelliJ.
 *
 * <p>The agent JAR is bundled inside the plugin JAR as a classpath resource
 * ({@code /agent/repoBuddy-agent.jar}). Each build produces a distinct content hash,
 * so the extracted file is named {@code repoBuddy-agent-<sha256>.jar}. This sidesteps
 * the Windows file-lock problem: a running JVM holds the old versioned file open while
 * the new version is written to a fresh path.
 */
public final class AgentRunConfigPatcher implements ProjectActivity, DumbAware {

    private static final Logger LOG = Logger.getInstance(AgentRunConfigPatcher.class);

    /** Resource path inside the plugin JAR. */
    private static final String AGENT_RESOURCE = "/agent/repoBuddy-agent.jar";
    /** Prefix for the versioned temp file and for detecting existing agent flags. */
    private static final String AGENT_MARKER = "repoBuddy-agent";

    @Override
    public Object execute(@NotNull Project project,
                          @NotNull Continuation<? super Unit> continuation) {
        Path agentJar = extractAgentJar();
        if (agentJar == null) {
            notify(project,
                    "RepoBuddy: agent JAR not found",
                    "Could not find the embedded agent JAR. "
                            + "Please reinstall the RepoBuddy plugin.",
                    NotificationType.WARNING);
            return Unit.INSTANCE;
        }

        // Normalise to forward slashes — works on all JVMs including Windows.
        String jvmFlag = "-javaagent:" + agentJar.toAbsolutePath().toString().replace('\\', '/');

        ApplicationManager.getApplication().invokeLater(
                () -> patchConfigurations(project, jvmFlag));

        return Unit.INSTANCE;
    }

    // -------------------------------------------------------------------------

    private static void patchConfigurations(Project project, String jvmFlag) {
        RunManager runManager = RunManager.getInstance(project);
        int patched = 0;

        for (RunnerAndConfigurationSettings settings : runManager.getAllSettings()) {
            if (!(settings.getConfiguration() instanceof CommonJavaRunConfigurationParameters cfg)) continue;

            String current = cfg.getVMParameters();

            if (current != null && current.contains(AGENT_MARKER)) {
                // Already present — update the path so the latest build's agent is used.
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
     * Reads the embedded agent JAR bytes, derives a SHA-256 hash, and writes the JAR
     * to {@code <tmpdir>/repoBuddy-agent-<hash>.jar} if that file doesn't already exist.
     *
     * <p>Using a content-addressed filename means each plugin build gets its own file.
     * A running JVM keeps the old versioned file open (Windows file-lock), but the new
     * version is written to a different path — so both can coexist safely.
     */
    @Nullable
    private static Path extractAgentJar() {
        byte[] jarBytes = readAgentJarBytes();
        if (jarBytes == null) return null;

        String hash = sha256Hex(jarBytes);
        if (hash == null) return null;

        String fileName = AGENT_MARKER + "-" + hash + ".jar";
        Path dest = Path.of(System.getProperty("java.io.tmpdir"), fileName);

        if (Files.exists(dest)) return dest; // already extracted for this build

        try {
            Files.write(dest, jarBytes);
            return dest;
        } catch (Exception e) {
            LOG.warn("RepoBuddy: failed to write agent JAR to " + dest, e);
            return null;
        }
    }

    /** Reads the entire agent JAR into memory, or returns {@code null} on failure. */
    @Nullable
    private static byte[] readAgentJarBytes() {
        try (InputStream in = openAgentJarStream()) {
            if (in == null) return null;
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            in.transferTo(buf);
            return buf.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    private static String sha256Hex(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            return HexFormat.of().formatHex(digest).substring(0, 16); // first 16 hex chars is enough
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Opens the agent JAR as a stream — first from the plugin classpath resource,
     * then from the agent module's Gradle build output (development-mode fallback).
     */
    @Nullable
    private static InputStream openAgentJarStream() {
        InputStream in = AgentRunConfigPatcher.class.getResourceAsStream(AGENT_RESOURCE);
        if (in != null) return in;
        // Fallback: when running via "Run Plugin" from the IDE the plugin JAR hasn't been
        // assembled, so the resource isn't on the classpath. Walk up to find the build output.
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
            LOG.warn("RepoBuddy: dev-mode agent JAR fallback search failed", e);
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
