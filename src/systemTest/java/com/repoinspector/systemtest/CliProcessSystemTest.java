package com.repoinspector.systemtest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** Uses java -jar from a freshly unpacked distribution, never RepoBuddyCli directly. */
class CliProcessSystemTest {
    @TempDir Path temporaryDirectory;

    @Test void helpVersionAndRulesWorkFromPackagedJar() {
        var version = SystemTestSupport.run(temporaryDirectory, "version");
        assertEquals(0, version.exitCode); assertTrue(version.stdout.contains("RepoBuddy")); assertEquals("", version.stderr);
        var help = SystemTestSupport.run(temporaryDirectory, "--help");
        assertEquals(0, help.exitCode); assertTrue(help.stdout.contains("repobuddy"));
        var rules = SystemTestSupport.run(temporaryDirectory, "--format", "json", "rules");
        assertEquals(0, rules.exitCode); assertTrue(rules.json().isJsonArray()); assertTrue(rules.stdout.contains("unsafe-query"));
    }

    @Test void invalidArgumentsUseContractExitCodeAndDiagnosticStream() {
        var unknown = SystemTestSupport.run(temporaryDirectory, "does-not-exist");
        assertEquals(2, unknown.exitCode); assertFalse(unknown.stderr.isBlank());
        var format = SystemTestSupport.run(temporaryDirectory, "--format", "xml", "rules");
        assertEquals(2, format.exitCode); assertTrue(format.stdout.isBlank());
        assertTrue(format.stderr.contains("REPOBUDDY_INVALID_ARGUMENT"));
    }

    @Test void missingIdeIntegrationHasStableExitCode() throws Exception {
        java.nio.file.Files.writeString(temporaryDirectory.resolve("pom.xml"), "<project/>");
        var result = SystemTestSupport.run(temporaryDirectory, "check");
        assertEquals(5, result.exitCode); assertTrue(result.stderr.contains("REPOBUDDY_IDE_NOT_RUNNING"));
    }
}
