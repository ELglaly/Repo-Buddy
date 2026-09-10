package com.repoinspector.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepoBuddyCliSupportTest {
    @TempDir Path temporaryDirectory;
    @Test void exposesCanonicalCodexSetupCommand() {
        assertEquals("repobuddy setup codex", RepoBuddyCliSupport.codexSetupCommand());
    }

    @Test void generatesCodexTomlAndGenericJsonForInstalledJar() throws Exception {
        byte[] archive = RepoBuddyCliInstallationTestArchive.valid("1.0.0");
        RepoBuddyCliInstallation installation = new RepoBuddyCliInstallation(
                temporaryDirectory.resolve("directory with spaces/cli"),
                () -> new java.io.ByteArrayInputStream(archive));
        RepoBuddyCliManager manager = new RepoBuddyCliManager(installation);

        String toml = manager.configuration(RepoBuddyCliManager.ConfigFormat.CODEX_TOML);
        String json = manager.configuration(RepoBuddyCliManager.ConfigFormat.GENERIC_JSON);
        String escapedJar = installation.jar().toString().replace("\\", "\\\\");
        assertTrue(toml.contains("[mcp_servers.repobuddy]"));
        assertTrue(toml.contains("args = [\"-jar\", \"" + escapedJar + "\", \"mcp\"]"));
        assertEquals("stdio", com.google.gson.JsonParser.parseString(json).getAsJsonObject()
                .getAsJsonObject("mcpServers").getAsJsonObject("repobuddy").get("type").getAsString());
    }

    private static final class RepoBuddyCliInstallationTestArchive {
        static byte[] valid(String version) throws Exception {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(bytes)) {
                for (var entry : java.util.Map.of("VERSION", version + "\n", "bin/repobuddy", "x",
                        "bin/repobuddy.bat", "x", "lib/repobuddy.jar", "x").entrySet()) {
                    zip.putNextEntry(new java.util.zip.ZipEntry(entry.getKey()));
                    zip.write(entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    zip.closeEntry();
                }
            }
            return bytes.toByteArray();
        }
    }
}
