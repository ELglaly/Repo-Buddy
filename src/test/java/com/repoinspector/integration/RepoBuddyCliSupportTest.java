package com.repoinspector.integration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RepoBuddyCliSupportTest {
    @Test void exposesCanonicalCodexSetupCommand() {
        assertEquals("repobuddy setup codex", RepoBuddyCliSupport.codexSetupCommand());
    }
}
