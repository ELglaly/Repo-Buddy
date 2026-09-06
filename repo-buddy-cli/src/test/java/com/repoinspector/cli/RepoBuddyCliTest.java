package com.repoinspector.cli;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.*;

class RepoBuddyCliTest {
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

    @Test void structuredFailuresMapToDocumentedExitCodes() {
        RepoBuddyCli cli = new RepoBuddyCli();
        cli.format = "human";
        assertEquals(5, cli.run(() -> { throw new com.repoinspector.core.RepoBuddyException(
                com.repoinspector.core.RepoBuddyErrorCode.REPOBUDDY_IDE_NOT_RUNNING, "offline"); }));
        assertEquals(6, cli.run(() -> { throw new com.repoinspector.core.RepoBuddyException(
                com.repoinspector.core.RepoBuddyErrorCode.REPOBUDDY_AUTHENTICATION_FAILED, "denied"); }));
    }
}
