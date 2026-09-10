package com.repoinspector.integration;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.repoinspector.settings.RepoBuddySettings;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Coordinates CLI installation and explicit, local AI-client registration. */
@Service
public final class RepoBuddyCliManager {
    private static final int MAX_OUTPUT_BYTES = 256 * 1024;

    public enum AiClient {
        CODEX("Codex", "codex"), CLAUDE_CODE("Claude Code", "claude");
        private final String displayName;
        private final String executable;
        AiClient(String displayName, String executable) {
            this.displayName = displayName;
            this.executable = executable;
        }
        public String displayName() { return displayName; }
        String executable() { return executable; }
        String setupName() { return this == CODEX ? "codex" : "claude"; }
    }

    public enum ConfigFormat { GENERIC_JSON, CODEX_TOML }
    public enum ResultKind { SUCCESS, CONFLICT, ERROR }
    public record Result(ResultKind kind, String message) {}

    private final RepoBuddyCliInstallation installation;

    public RepoBuddyCliManager() { this(new RepoBuddyCliInstallation()); }
    RepoBuddyCliManager(RepoBuddyCliInstallation installation) { this.installation = installation; }

    public static @NotNull RepoBuddyCliManager getInstance() {
        return ApplicationManager.getApplication().getService(RepoBuddyCliManager.class);
    }

    RepoBuddyCliInstallation.Info inspect() { return installation.inspect(); }
    RepoBuddyCliInstallation.Info install() throws IOException { return installation.install(); }
    public Path installationDirectory() { return installation.inspect().directory(); }
    public Path binDirectory() { return installation.bin(); }
    public boolean isInstalledCurrent() { return inspect().state() == RepoBuddyCliInstallation.State.CURRENT; }

    public String installationStatusText() {
        RepoBuddyCliInstallation.Info info = inspect();
        return switch (info.state()) {
            case NOT_INSTALLED -> "RepoBuddy CLI: not installed";
            case CURRENT -> "RepoBuddy CLI " + info.installedVersion() + " installed at " + info.directory();
            case OUTDATED -> "RepoBuddy CLI " + info.installedVersion() + " installed; "
                    + info.bundledVersion() + " is available";
            case BROKEN -> "RepoBuddy CLI installation is incomplete and can be repaired";
        };
    }

    public String installActionText() {
        return switch (inspect().state()) {
            case NOT_INSTALLED -> "Install RepoBuddy CLI";
            case CURRENT -> "RepoBuddy CLI Installed";
            case OUTDATED -> "Update RepoBuddy CLI";
            case BROKEN -> "Repair RepoBuddy CLI";
        };
    }

    public @NotNull Result configure(@NotNull Project project, @NotNull AiClient client, boolean replace) {
        boolean wasEnabled = RepoBuddySettings.getInstance().isLocalIntegrationEnabled();
        try {
            if (project.isDisposed() || project.getBasePath() == null)
                return new Result(ResultKind.ERROR, "The selected project is no longer available.");
            install();
            RepoBuddySettings.getInstance().setLocalIntegrationEnabled(true);
            RepoBuddyLocalApiServer server = RepoBuddyLocalApiServer.getInstance();
            server.enable();
            server.register(project);

            Path clientExecutable = RepoBuddyCliSupport.findExecutable(client.executable());
            if (clientExecutable == null)
                return failed(wasEnabled, "The " + client.displayName() + " CLI was not found on PATH.");
            Path java = javaExecutable();
            if (java == null) return failed(wasEnabled, "Java 17 or newer could not be located.");

            List<String> command = new ArrayList<>(List.of(java.toString(), "-jar", installation.jar().toString(),
                    "setup", client.setupName()));
            if (replace) command.add("--replace");
            ProcessResult process = run(Path.of(project.getBasePath()), command, Duration.ofSeconds(90));
            if (process.exitCode() == 0)
                return new Result(ResultKind.SUCCESS, client.displayName() + " is configured for RepoBuddy.");
            if (process.output().contains("already exists")) {
                restoreIntegration(wasEnabled);
                return new Result(ResultKind.CONFLICT, process.output());
            }
            return failed(wasEnabled, process.output().isBlank()
                    ? client.displayName() + " configuration failed with exit code " + process.exitCode()
                    : process.output());
        } catch (Exception error) {
            restoreIntegration(wasEnabled);
            return new Result(ResultKind.ERROR, error.getMessage() == null ? "RepoBuddy setup failed" : error.getMessage());
        }
    }

    public @NotNull String configuration(@NotNull ConfigFormat format) throws IOException {
        install();
        Path java = javaExecutable();
        if (java == null) throw new IOException("Java 17 or newer could not be located");
        if (format == ConfigFormat.CODEX_TOML) {
            return "[mcp_servers.repobuddy]\ncommand = \"" + toml(java.toString()) + "\"\n"
                    + "args = [\"-jar\", \"" + toml(installation.jar().toString()) + "\", \"mcp\"]\n";
        }
        JsonObject server = new JsonObject();
        server.addProperty("type", "stdio");
        server.addProperty("command", java.toString());
        JsonArray arguments = new JsonArray();
        arguments.add("-jar"); arguments.add(installation.jar().toString()); arguments.add("mcp");
        server.add("args", arguments);
        server.add("env", new JsonObject());
        JsonObject servers = new JsonObject(); servers.add("repobuddy", server);
        JsonObject root = new JsonObject(); root.add("mcpServers", servers);
        return new GsonBuilder().setPrettyPrinting().create().toJson(root) + System.lineSeparator();
    }

    public String pathGuidance() {
        String bin = installation.bin().toString();
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        if (windows) return "Add this directory to your user PATH, then open a new terminal:\n\n" + bin;
        return "Add this line to your shell profile, then open a new terminal:\n\nexport PATH=\""
                + bin.replace("\\", "\\\\").replace("\"", "\\\"") + ":$PATH\"";
    }

    private Result failed(boolean wasEnabled, String message) {
        restoreIntegration(wasEnabled);
        return new Result(ResultKind.ERROR, message);
    }

    private void restoreIntegration(boolean wasEnabled) {
        if (wasEnabled) return;
        RepoBuddySettings.getInstance().setLocalIntegrationEnabled(false);
        RepoBuddyLocalApiServer.getInstance().disable();
    }

    static Path javaExecutable() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Path bundled = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java");
        if (Files.isRegularFile(bundled)) return bundled.toAbsolutePath().normalize();
        return RepoBuddyCliSupport.findExecutable("java");
    }

    private static String toml(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private record ProcessResult(int exitCode, String output) {}

    private static ProcessResult run(Path directory, List<String> command, Duration timeout)
            throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> drain(process.getInputStream(), captured), "RepoBuddy CLI setup output");
        reader.setDaemon(true);
        reader.start();
        boolean completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!completed) {
            process.destroyForcibly();
            reader.join(1_000);
            return new ProcessResult(124, "RepoBuddy setup timed out.");
        }
        reader.join(1_000);
        return new ProcessResult(process.exitValue(), captured.toString(StandardCharsets.UTF_8).trim());
    }

    private static void drain(InputStream input, ByteArrayOutputStream captured) {
        try (input) {
            byte[] buffer = new byte[8 * 1024];
            int retained = 0;
            for (int read; (read = input.read(buffer)) >= 0;) {
                if (retained >= MAX_OUTPUT_BYTES) continue;
                int length = Math.min(read, MAX_OUTPUT_BYTES - retained);
                captured.write(buffer, 0, length);
                retained += length;
            }
        } catch (IOException ignored) { }
    }
}
