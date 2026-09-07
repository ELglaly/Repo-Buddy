package com.repoinspector.cli;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

final class InstallationLayout {
    private InstallationLayout() {}

    static Path runningArtifact() {
        try {
            URI location = InstallationLayout.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            return Path.of(location).toAbsolutePath().normalize();
        } catch (Exception error) {
            return null;
        }
    }

    static boolean isPackagedJar() {
        Path artifact = runningArtifact();
        return artifact != null && Files.isRegularFile(artifact) && artifact.getFileName().toString().endsWith(".jar");
    }
}
