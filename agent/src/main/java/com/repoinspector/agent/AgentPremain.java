package com.repoinspector.agent;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.net.URI;
import java.util.jar.JarFile;

/**
 * Java-agent entry point bundled inside the RepoBuddy IntelliJ plugin.
 *
 * <p>The IntelliJ plugin automatically injects {@code -javaagent:/path/to/repoBuddy-agent.jar}
 * into every Java/Spring Boot run configuration.  When the user's Spring Boot application
 * starts, the JVM calls {@link #premain} before {@code main()}.
 *
 * <p>All this method does is add the agent JAR to the system class loader's search path.
 * Spring Boot then discovers {@code META-INF/spring/org.springframework.boot.autoconfigure
 * .AutoConfiguration.imports} inside the JAR, loads {@code RepoExecutionAutoConfiguration},
 * and registers {@code RepoBuddyAgentServer} as a Spring bean.  The bean starts a lightweight
 * HTTP server on port 19090 — completely outside Tomcat and Spring Security, so no
 * authentication is ever involved.
 *
 * <p>This class deliberately references no Spring types; it is loaded by the bootstrap /
 * instrument class loader, while Spring loads the auto-configuration via the application
 * class loader after {@link Instrumentation#appendToSystemClassLoaderSearch}.
 */
public final class AgentPremain {

    private AgentPremain() {}

    /** Called by the JVM before {@code main()} when launched with {@code -javaagent}. */
    public static void premain(String agentArgs, Instrumentation inst) throws Exception {
        addToSystemClasspath(inst);
    }

    /** Called when the agent is loaded dynamically via the Attach API. */
    public static void agentmain(String agentArgs, Instrumentation inst) throws Exception {
        addToSystemClasspath(inst);
    }

    private static void addToSystemClasspath(Instrumentation inst) throws Exception {
        URI location = AgentPremain.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI();
        inst.appendToSystemClassLoaderSearch(new JarFile(new File(location)));
    }
}