package com.repoinspector.runner.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SpringAppUrlResolverTest {

    private Path writtenPortFile;

    @AfterEach
    void cleanup() throws IOException {
        if (writtenPortFile != null) {
            Files.deleteIfExists(writtenPortFile);
        }
    }

    @Test
    void portFilePath_usesSystemTempDir() {
        Path path = SpringAppUrlResolver.portFilePath();
        assertEquals(SpringAppUrlResolver.PORT_FILE_NAME, path.getFileName().toString());
        assertTrue(path.startsWith(Path.of(System.getProperty("java.io.tmpdir"))));
    }

    @Test
    void resolve_noPortFile_returnsNull() {
        Path portFile = SpringAppUrlResolver.portFilePath();
        try {
            Files.deleteIfExists(portFile);
        } catch (IOException ignored) {}

        assertNull(SpringAppUrlResolver.resolve(null));
    }

    @Test
    void resolve_validPortFile_returnsUrl() throws IOException {
        writtenPortFile = SpringAppUrlResolver.portFilePath();
        Files.writeString(writtenPortFile, "54321");

        String url = SpringAppUrlResolver.resolve(null);
        assertEquals("http://localhost:54321", url);
    }

    @Test
    void resolve_portFileWithWhitespace_trimsAndReturnsUrl() throws IOException {
        writtenPortFile = SpringAppUrlResolver.portFilePath();
        Files.writeString(writtenPortFile, "  8080\n");

        String url = SpringAppUrlResolver.resolve(null);
        assertEquals("http://localhost:8080", url);
    }

    @Test
    void resolve_invalidPortContent_returnsNull() throws IOException {
        writtenPortFile = SpringAppUrlResolver.portFilePath();
        Files.writeString(writtenPortFile, "not-a-number");

        assertNull(SpringAppUrlResolver.resolve(null));
    }
}
