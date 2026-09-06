package com.repoinspector.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SourceContextReaderTest {
    @TempDir Path root;

    @Test void clipsAtStartAndEndOfFile() throws Exception {
        Files.writeString(root.resolve("A.java"), "one\ntwo\nthree\n");
        var start = SourceContextReader.read(root, "A.java", 1, 10, 50, 32 * 1024);
        assertEquals(1, start.startLine());
        assertEquals(3, start.endLine());
        var end = SourceContextReader.read(root, "A.java", 3, 1, 50, 32 * 1024);
        assertEquals(2, end.startLine());
        assertEquals(3, end.endLine());
    }

    @Test void rejectsTraversalAndAbsolutePaths() throws Exception {
        Files.writeString(root.resolve("A.java"), "code");
        RepoBuddyException traversal = assertThrows(RepoBuddyException.class,
                () -> SourceContextReader.read(root, "../A.java", 1, 1, 50, 100));
        assertEquals(RepoBuddyErrorCode.REPOBUDDY_PATH_OUTSIDE_PROJECT, traversal.code());
        assertThrows(RepoBuddyException.class,
                () -> SourceContextReader.read(root, root.resolve("A.java").toString(), 1, 1, 50, 100));
    }

    @Test void rejectsDeletedFileAndMaximumContext() {
        RepoBuddyException missing = assertThrows(RepoBuddyException.class,
                () -> SourceContextReader.read(root, "missing.java", 1, 1, 50, 100));
        assertEquals(RepoBuddyErrorCode.REPOBUDDY_ISSUE_NOT_FOUND, missing.code());
        assertThrows(RepoBuddyException.class,
                () -> SourceContextReader.read(root, "missing.java", 1, 51, 50, 100));
    }

    @Test void truncatesOversizedSourcePayload() throws Exception {
        Files.writeString(root.resolve("Large.java"), "x".repeat(100) + "\nsecond\n");
        var result = SourceContextReader.read(root, "Large.java", 1, 1, 50, 32);
        assertTrue(result.truncated());
        assertTrue(result.code().getBytes().length <= 32);
    }

    @Test void rejectsSymlinkEscapeWhenSupported() throws Exception {
        Path outside = Files.createTempFile("repobuddy-outside", ".java");
        Path link = root.resolve("Link.java");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException unsupported) {
            return;
        }
        try {
            assertThrows(RepoBuddyException.class,
                    () -> SourceContextReader.read(root, "Link.java", 1, 1, 50, 100));
        } finally { Files.deleteIfExists(outside); }
    }
}
