package com.repoinspector.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

final class ProcessSupport {
    record Result(int exitCode, String output) { boolean succeeded() { return exitCode == 0; } }
    private ProcessSupport() {}

    static Path findExecutable(String name) {
        String path = System.getenv("PATH");
        if (path == null) return null;
        List<String> suffixes = isWindows() ? List.of(".exe", ".cmd", ".bat", "") : List.of("");
        for (String entry : path.split(java.io.File.pathSeparator)) {
            if (entry.isBlank()) continue;
            for (String suffix : suffixes) {
                Path candidate = Path.of(entry, name + suffix);
                if (Files.isRegularFile(candidate) && (isWindows() || Files.isExecutable(candidate))) return candidate.toAbsolutePath().normalize();
            }
        }
        return null;
    }

    static Result run(Path directory, List<String> command, int timeoutSeconds) {
        try {
            List<String> actual = new ArrayList<>(command);
            if (isWindows() && !actual.isEmpty() && (actual.get(0).toLowerCase(Locale.ROOT).endsWith(".cmd")
                    || actual.get(0).toLowerCase(Locale.ROOT).endsWith(".bat"))) {
                String joined = actual.stream().map(ProcessSupport::quoteWindows).collect(java.util.stream.Collectors.joining(" "));
                actual = List.of("cmd.exe", "/d", "/s", "/c", joined);
            }
            ProcessBuilder builder = new ProcessBuilder(actual).redirectErrorStream(true);
            if (directory != null) builder.directory(directory.toFile());
            Process process = builder.start();
            java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
            Thread reader = new Thread(() -> {
                try { process.getInputStream().transferTo(captured); } catch (IOException ignored) { }
            }, "RepoBuddy command output");
            reader.setDaemon(true);
            reader.start();
            boolean finished = process.waitFor(Math.max(1, timeoutSeconds), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new Result(124, "Command timed out");
            }
            reader.join(1_000);
            return new Result(process.exitValue(), captured.toString(StandardCharsets.UTF_8).trim());
        } catch (IOException error) {
            return new Result(127, error.getMessage() == null ? "Unable to start command" : error.getMessage());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return new Result(130, "Command interrupted");
        }
    }

    static Path javaExecutable() {
        Path homeJava = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java");
        return Files.isRegularFile(homeJava) ? homeJava.toAbsolutePath().normalize() : findExecutable("java");
    }

    private static boolean isWindows() { return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"); }
    private static String quoteWindows(String value) { return '"' + value.replace("\"", "\"\"") + '"'; }
}
