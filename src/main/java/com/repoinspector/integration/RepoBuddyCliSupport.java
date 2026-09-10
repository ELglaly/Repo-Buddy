package com.repoinspector.integration;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** Lightweight CLI discovery and copyable setup guidance for the settings UI. */
public final class RepoBuddyCliSupport {
    private RepoBuddyCliSupport() {}

    public static String codexSetupCommand() { return "repobuddy setup codex"; }

    public static Path findOnPath() { return findExecutable("repobuddy"); }

    public static Path findExecutable(String executable) {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) return null;
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        List<String> names = windows ? List.of(executable + ".exe", executable + ".cmd", executable + ".bat", executable)
                : List.of(executable);
        for (String directory : path.split(java.io.File.pathSeparator)) {
            if (directory.isBlank()) continue;
            for (String name : names) {
                try {
                    Path candidate = Path.of(directory, name);
                    if (Files.isRegularFile(candidate)) return candidate.toAbsolutePath().normalize();
                } catch (InvalidPathException ignored) { }
            }
        }
        return null;
    }
}
