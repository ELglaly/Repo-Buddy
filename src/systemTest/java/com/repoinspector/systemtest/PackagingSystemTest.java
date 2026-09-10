package com.repoinspector.systemtest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class PackagingSystemTest {
    @TempDir Path temporaryDirectory;
    @Test void packagedJarIsSelfContainedAndDoesNotNeedTheCheckout() throws Exception {
        Path jar = SystemTestSupport.packagedJar();
        assertTrue(Files.isRegularFile(jar));
        Path freshDirectory = Files.createDirectory(temporaryDirectory.resolve("fresh directory with spaces"));
        var version = SystemTestSupport.run(freshDirectory, "version");
        assertEquals(0, version.exitCode); assertTrue(version.stdout.contains("RepoBuddy"));
    }
}
