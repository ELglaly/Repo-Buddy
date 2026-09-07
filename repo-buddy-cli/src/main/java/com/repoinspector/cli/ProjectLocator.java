package com.repoinspector.cli;

import com.repoinspector.core.RepoBuddyErrorCode;
import com.repoinspector.core.RepoBuddyException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class ProjectLocator {
    private ProjectLocator() {}

    static Path resolve(Path explicit, Path workingDirectory) {
        Path start = explicit == null ? workingDirectory : explicit;
        if (start == null) start = Path.of(".");
        start = start.toAbsolutePath().normalize();
        if (!Files.exists(start)) throw new RepoBuddyException(RepoBuddyErrorCode.REPOBUDDY_PROJECT_NOT_FOUND,
                "Project path does not exist: " + start);
        if (!Files.isDirectory(start)) start = start.getParent();
        Path canonical = realOrNormalized(start);
        Path git = nearest(canonical, path -> Files.exists(path.resolve(".git")));
        if (git != null) return git;
        Path build = nearest(canonical, path -> Files.exists(path.resolve("pom.xml"))
                || Files.exists(path.resolve("build.gradle")) || Files.exists(path.resolve("build.gradle.kts")));
        if (build != null) return build;
        throw new RepoBuddyException(RepoBuddyErrorCode.REPOBUDDY_PROJECT_NOT_SUPPORTED,
                "No Git, Maven, or Gradle project was found from " + canonical);
    }

    static boolean isGitRepository(Path root) {
        return root != null && Files.exists(root.resolve(".git"));
    }

    private static Path nearest(Path start, java.util.function.Predicate<Path> marker) {
        for (Path value = start; value != null; value = value.getParent()) if (marker.test(value)) return value;
        return null;
    }

    private static Path realOrNormalized(Path path) {
        try { return path.toRealPath(); }
        catch (IOException ignored) { return path.toAbsolutePath().normalize(); }
    }
}
