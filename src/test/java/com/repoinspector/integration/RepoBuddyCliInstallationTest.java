package com.repoinspector.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class RepoBuddyCliInstallationTest {
    @TempDir Path temporaryDirectory;

    @Test void installsCompleteDistributionAndDetectsCurrentVersion() throws Exception {
        RepoBuddyCliInstallation installation = installation(validDistribution("2.0.0"));
        RepoBuddyCliInstallation.Info info = installation.install();

        assertEquals(RepoBuddyCliInstallation.State.CURRENT, info.state());
        assertEquals("2.0.0", info.installedVersion());
        assertTrue(Files.isRegularFile(installation.jar()));
        assertEquals(info, installation.inspect());
    }

    @Test void detectsAndReplacesOutdatedDistribution() throws Exception {
        Path target = temporaryDirectory.resolve("RepoBuddy/cli");
        RepoBuddyCliInstallation first = new RepoBuddyCliInstallation(target, bytes(validDistribution("1.0.0")));
        first.install();
        RepoBuddyCliInstallation second = new RepoBuddyCliInstallation(target, bytes(validDistribution("2.0.0")));

        assertEquals(RepoBuddyCliInstallation.State.OUTDATED, second.inspect().state());
        assertEquals(RepoBuddyCliInstallation.State.CURRENT, second.install().state());
        assertEquals("2.0.0", Files.readString(target.resolve("VERSION")).trim());
    }

    @Test void rejectsTraversalWithoutWritingOutsideInstallation() throws Exception {
        byte[] archive = archive(Map.of("VERSION", "2.0.0\n", "../escaped.txt", "bad"));
        RepoBuddyCliInstallation installation = installation(archive);

        assertThrows(java.io.IOException.class, installation::install);
        assertFalse(Files.exists(temporaryDirectory.resolve("RepoBuddy/escaped.txt")));
        assertFalse(Files.exists(temporaryDirectory.resolve("RepoBuddy/cli")));
    }

    @Test void reportsBrokenInstallation() throws Exception {
        Path target = temporaryDirectory.resolve("RepoBuddy/cli");
        Files.createDirectories(target);
        Files.writeString(target.resolve("VERSION"), "2.0.0");
        RepoBuddyCliInstallation installation = new RepoBuddyCliInstallation(target, bytes(validDistribution("2.0.0")));
        assertEquals(RepoBuddyCliInstallation.State.BROKEN, installation.inspect().state());
    }

    @Test void failedUpdateLeavesPreviousInstallationUsable() throws Exception {
        Path target = temporaryDirectory.resolve("RepoBuddy/cli");
        RepoBuddyCliInstallation first = new RepoBuddyCliInstallation(target, bytes(validDistribution("1.0.0")));
        first.install();
        RepoBuddyCliInstallation brokenUpdate = new RepoBuddyCliInstallation(target,
                bytes(archive(Map.of("VERSION", "2.0.0\n", "lib/repobuddy.jar", "jar"))));

        assertThrows(java.io.IOException.class, brokenUpdate::install);
        assertEquals("1.0.0", Files.readString(target.resolve("VERSION")).trim());
        assertTrue(Files.isRegularFile(target.resolve("bin/repobuddy.bat")));
    }

    @Test void resolvesPlatformApplicationDataDirectories() {
        Path home = Path.of("users", "me");
        assertEquals(Path.of("C:/Local/RepoBuddy/cli"), RepoBuddyCliInstallation.defaultDirectory(
                Map.of("LOCALAPPDATA", "C:/Local"), "Windows 11", home));
        assertEquals(home.resolve("Library/Application Support/RepoBuddy/cli"),
                RepoBuddyCliInstallation.defaultDirectory(Map.of(), "Mac OS X", home));
        if (java.io.File.separatorChar == '/') {
            assertEquals(Path.of("/data/RepoBuddy/cli"), RepoBuddyCliInstallation.defaultDirectory(
                    Map.of("XDG_DATA_HOME", "/data"), "Linux", home));
        }
        assertEquals(home.resolve(".local/share/RepoBuddy/cli"),
                RepoBuddyCliInstallation.defaultDirectory(Map.of(), "Linux", home));
    }

    private RepoBuddyCliInstallation installation(byte[] archive) {
        return new RepoBuddyCliInstallation(temporaryDirectory.resolve("RepoBuddy/cli"), bytes(archive));
    }

    private static Supplier<java.io.InputStream> bytes(byte[] archive) {
        return () -> new ByteArrayInputStream(archive);
    }

    private static byte[] validDistribution(String version) throws Exception {
        return archive(Map.of(
                "VERSION", version + "\n",
                "bin/repobuddy", "#!/bin/sh\n",
                "bin/repobuddy.bat", "@echo off\n",
                "lib/repobuddy.jar", "jar"));
    }

    private static byte[] archive(Map<String, String> entries) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
