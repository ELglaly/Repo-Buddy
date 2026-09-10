package com.repoinspector.systemtest;

import com.repoinspector.ipc.SessionDiscovery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ContractSystemTest {
    @TempDir Path temporaryDirectory;

    @Test void sessionDirectoryOverrideIsExplicitAndDoesNotUseUserCache() throws Exception {
        String previous = System.getProperty(SessionDiscovery.SESSION_DIRECTORY_PROPERTY);
        Path isolated = temporaryDirectory.resolve("session descriptors");
        try { System.setProperty(SessionDiscovery.SESSION_DIRECTORY_PROPERTY, isolated.toString()); assertEquals(isolated.toAbsolutePath().normalize(), SessionDiscovery.directory()); assertFalse(Files.exists(isolated)); }
        finally { if (previous == null) System.clearProperty(SessionDiscovery.SESSION_DIRECTORY_PROPERTY); else System.setProperty(SessionDiscovery.SESSION_DIRECTORY_PROPERTY, previous); }
    }
}
