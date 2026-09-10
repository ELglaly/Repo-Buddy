package com.repoinspector.systemtest;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.repoinspector.core.RepoBuddyJson;
import org.junit.jupiter.api.Assertions;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Bounded black-box process and JSONL helpers for packaged CLI system tests. */
final class SystemTestSupport {
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(20);

    static Path packagedJar() {
        Path jar = Path.of("build", "distributions", "repobuddy-cli-" + System.getProperty("repobuddy.version", "1.0.8") + ".zip");
        if (!Files.isRegularFile(jar)) {
            // Gradle's version is intentionally not duplicated in tests; resolve the produced archive.
            try (var files = Files.list(jar.getParent())) {
                Path archive = files.filter(path -> path.getFileName().toString().matches("repobuddy-cli-.+\\.zip"))
                        .findFirst().orElseThrow(() -> new AssertionError("CLI distribution is absent; run :repo-buddy-cli:cliDistZip"));
                Path unpacked = Files.createTempDirectory("repobuddy packaged cli ");
                unzip(archive, unpacked);
                return unpacked.resolve("lib/repobuddy.jar");
            } catch (IOException error) { throw new AssertionError("Unable to locate packaged CLI", error); }
        }
        try {
            Path unpacked = Files.createTempDirectory("repobuddy packaged cli ");
            unzip(jar, unpacked);
            return unpacked.resolve("lib/repobuddy.jar");
        } catch (IOException error) { throw new AssertionError("Unable to unpack packaged CLI", error); }
    }

    static Result run(Path workingDirectory, String... args) {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-Drepobuddy.session.directory=" + workingDirectory.resolve(".repobuddy-system-sessions").toAbsolutePath());
        command.add("-jar"); command.add(packagedJar().toString());
        command.addAll(List.of(args));
        try {
            Process process = new ProcessBuilder(command).directory(workingDirectory.toFile()).start();
            boolean completed = process.waitFor(DEFAULT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!completed) { process.destroyForcibly(); throw new AssertionError("CLI timed out: " + command); }
            return new Result(process.exitValue(), new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8),
                    new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException | InterruptedException error) {
            Thread.currentThread().interrupt(); throw new AssertionError("Unable to run packaged CLI", error);
        }
    }

    static McpProcess startMcp(Path workingDirectory) {
        List<String> command = List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Drepobuddy.session.directory=" + workingDirectory.resolve(".repobuddy-system-sessions").toAbsolutePath(),
                "-jar", packagedJar().toString(), "mcp");
        try { return new McpProcess(new ProcessBuilder(command).directory(workingDirectory.toFile()).start()); }
        catch (IOException error) { throw new AssertionError("Unable to start packaged MCP child", error); }
    }

    static void assertJson(String value) { Assertions.assertDoesNotThrow(() -> JsonParser.parseString(value)); }

    static final class Result {
        final int exitCode; final String stdout; final String stderr;
        Result(int exitCode, String stdout, String stderr) { this.exitCode = exitCode; this.stdout = stdout; this.stderr = stderr; }
        JsonElement json() { return JsonParser.parseString(stdout); }
    }

    static final class McpProcess implements AutoCloseable {
        private final Process process;
        private final BufferedReader stdout;
        private final BufferedReader stderr;
        private final OutputStreamWriter stdin;
        McpProcess(Process process) {
            this.process = process;
            stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            stderr = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8));
            stdin = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
        }
        JsonObject request(JsonObject request) {
            try {
                stdin.write(RepoBuddyJson.gson(false).toJson(request)); stdin.write("\n"); stdin.flush();
                String line = stdout.readLine();
                Assertions.assertNotNull(line, "MCP child closed unexpectedly");
                return JsonParser.parseString(line).getAsJsonObject();
            } catch (IOException error) { throw new AssertionError("MCP JSONL exchange failed", error); }
        }
        @Override public void close() {
            try { stdin.close(); } catch (IOException ignored) { }
            process.destroy();
            try { if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly(); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); process.destroyForcibly(); }
        }
    }

    private static void unzip(Path archive, Path destination) throws IOException {
        try (var zip = new java.util.zip.ZipInputStream(Files.newInputStream(archive))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                Path output = destination.resolve(entry.getName()).normalize();
                if (!output.startsWith(destination)) throw new IOException("Unsafe distribution entry: " + entry.getName());
                if (entry.isDirectory()) Files.createDirectories(output);
                else { Files.createDirectories(output.getParent()); Files.copy(zip, output); }
            }
        }
    }
}
