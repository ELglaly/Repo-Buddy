package com.repoinspector.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class RepoBuddyCliTest {
    @TempDir Path temporaryDirectory;

    @Test void rulesCommandWorksWithoutIntelliJ() {
        PrintStream original = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(output));
            int exit = new CommandLine(new RepoBuddyCli()).execute("rules", "--format", "json");
            assertEquals(0, exit);
            assertTrue(output.toString().contains("n-plus-one"));
        } finally { System.setOut(original); }
    }

    @Test void invalidIssuesLimitReturnsArgumentExitCode() {
        int exit = new CommandLine(new RepoBuddyCli()).execute("issues", "--limit", "201");
        assertEquals(2, exit);
    }

    @Test void helpIsAvailable() {
        assertEquals(0, new CommandLine(new RepoBuddyCli()).execute("--help"));
    }

    @Test void canonicalCommandsAreRegisteredAndLegacyScanIsRemoved() {
        CommandLine command = new CommandLine(new RepoBuddyCli());
        assertTrue(command.getSubcommands().keySet().containsAll(java.util.Set.of(
                "setup", "mcp", "check", "status", "doctor", "version", "help")));
        assertFalse(command.getSubcommands().containsKey("scan"));
        assertEquals(2, command.execute("scan"));
    }

    @Test void versionCommandIsAvailable() {
        assertEquals(0, new CommandLine(new RepoBuddyCli()).execute("version"));
    }

    @Test void projectDetectionWalksFromNestedDirectoryToGitRoot() throws Exception {
        Path repository = temporaryDirectory.resolve("project");
        Path nested = repository.resolve("src/main/java");
        Files.createDirectories(repository.resolve(".git"));
        Files.createDirectories(nested);
        assertEquals(repository.toRealPath(), ProjectLocator.resolve(null, nested));
    }

    @Test void explicitProjectFallsBackToBuildRootOutsideGit() throws Exception {
        Path project = temporaryDirectory.resolve("plain project");
        Path nested = project.resolve("src");
        Files.createDirectories(nested);
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        assertEquals(project.toRealPath(), ProjectLocator.resolve(nested, temporaryDirectory));
        assertFalse(ProjectLocator.isGitRepository(project));
    }

    @Test void structuredFailuresMapToDocumentedExitCodes() {
        RepoBuddyCli cli = new RepoBuddyCli();
        cli.format = "human";
        assertEquals(5, cli.run(() -> { throw new com.repoinspector.core.RepoBuddyException(
                com.repoinspector.core.RepoBuddyErrorCode.REPOBUDDY_IDE_NOT_RUNNING, "offline"); }));
        assertEquals(6, cli.run(() -> { throw new com.repoinspector.core.RepoBuddyException(
                com.repoinspector.core.RepoBuddyErrorCode.REPOBUDDY_AUTHENTICATION_FAILED, "denied"); }));
    }
}
