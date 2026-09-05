package com.repoinspector.runner.startup;

import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Resolves the bundled agent at launch time without retaining an installation-specific path. */
public final class RepoBuddyAgentPathResolver {
    private static final Logger LOG = Logger.getInstance(RepoBuddyAgentPathResolver.class);
    private RepoBuddyAgentPathResolver() { }
    @Nullable public static Path resolve() {
        try (InputStream input = RepoBuddyAgentPathResolver.class.getResourceAsStream("/agent/repoBuddy-agent.jar")) {
            if (input == null) { LOG.warn("RepoBuddy: bundled Java agent resource is missing"); return null; }
            ByteArrayOutputStream output = new ByteArrayOutputStream(); input.transferTo(output); byte[] bytes = output.toByteArray();
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).substring(0, 16);
            Path destination = Path.of(System.getProperty("java.io.tmpdir"), "repoBuddy-agent-" + hash + ".jar");
            if (!Files.exists(destination)) Files.write(destination, bytes);
            return Files.isRegularFile(destination) ? destination : null;
        } catch (Exception e) { LOG.warn("RepoBuddy: failed to resolve bundled Java agent", e); return null; }
    }
}
