package com.repoinspector.runner.service;

import com.intellij.openapi.project.Project;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves the RepoBuddy agent URL.
 *
 * <p>When the Spring Boot application starts with the RepoBuddy agent JAR on its
 * classpath (injected automatically via {@code -javaagent} by the plugin), the agent
 * binds to a random free port chosen by the OS and writes that port number to
 * {@value #PORT_FILE_NAME} in the system temp directory.
 *
 * <p>This resolver simply reads that file — no {@code application.properties} parsing,
 * no fixed port, no possible clash with {@code server.port} or Spring Security.
 */
public final class SpringAppUrlResolver {

    /** Must match {@code RepoBuddyAgentServer.PORT_FILE_NAME} in the agent module. */
    public static final String PORT_FILE_NAME = "repoBuddy-agent.port";

    private SpringAppUrlResolver() {}

    /**
     * Returns the base URL of the running RepoBuddy agent, e.g.
     * {@code "http://localhost:54321"}, by reading the port file the agent wrote on
     * startup.  Returns {@code null} if the port file does not exist (agent not running).
     *
     * <p>The {@code project} parameter is kept for API compatibility but is no longer used.
     */
    public static String resolve(Project project) {
        Path portFile = portFilePath();
        if (!Files.exists(portFile)) return null;
        try {
            int port = Integer.parseInt(Files.readString(portFile).trim());
            return "http://localhost:" + port;
        } catch (Exception e) {
            return null;
        }
    }

    /** Path to the temp file written by the agent. */
    public static Path portFilePath() {
        return Path.of(System.getProperty("java.io.tmpdir"), PORT_FILE_NAME);
    }
}
