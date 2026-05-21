package com.repoinspector.settings;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the {@link RepoBuddySettings} state component (no platform required — exercises
 * the POJO directly, independent of the application service registry).
 */
class RepoBuddySettingsTest {

    @Test
    void panelOnlyMode_defaultsToTrue() {
        assertTrue(new RepoBuddySettings().isPanelOnlyMode());
    }

    @Test
    void setPanelOnlyMode_roundTrips() {
        RepoBuddySettings settings = new RepoBuddySettings();
        settings.setPanelOnlyMode(false);
        assertFalse(settings.isPanelOnlyMode());
        settings.setPanelOnlyMode(true);
        assertTrue(settings.isPanelOnlyMode());
    }

    @Test
    void getState_reflectsCurrentValue() {
        RepoBuddySettings settings = new RepoBuddySettings();
        settings.setPanelOnlyMode(false);
        assertFalse(settings.getState().panelOnlyMode);
    }

    @Test
    void loadState_replacesValue() {
        RepoBuddySettings settings = new RepoBuddySettings();
        RepoBuddySettings.State persisted = new RepoBuddySettings.State();
        persisted.panelOnlyMode = false;

        settings.loadState(persisted);

        assertFalse(settings.isPanelOnlyMode());
        assertSame(persisted, settings.getState());
    }
}
