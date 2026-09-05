package com.repoinspector.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;

/**
 * Persisted RepoBuddy preferences (application level).
 *
 * <p>{@code panelOnlyMode} (default {@code true}) controls how the five RepoBuddy inspections
 * surface: when on, they do not run in the editor daemon (no inline underlines, no Problems-view
 * entries) and findings appear only in the Issues panel + indicators; when off, the inspections
 * behave like ordinary local inspections (dual mode).
 */
@Service
@State(name = "RepoBuddySettings", storages = @Storage("repobuddy.xml"))
public final class RepoBuddySettings implements PersistentStateComponent<RepoBuddySettings.State> {

    public static final class State {
        public boolean panelOnlyMode = true;
        public boolean javaAgentEnabled = true;
    }

    private State state = new State();

    public static @NotNull RepoBuddySettings getInstance() {
        return ApplicationManager.getApplication().getService(RepoBuddySettings.class);
    }

    public boolean isPanelOnlyMode() {
        return state.panelOnlyMode;
    }

    public void setPanelOnlyMode(boolean panelOnlyMode) {
        state.panelOnlyMode = panelOnlyMode;
    }

    public boolean isJavaAgentEnabled() { return state.javaAgentEnabled; }

    public void setJavaAgentEnabled(boolean javaAgentEnabled) { state.javaAgentEnabled = javaAgentEnabled; }

    @Override
    public @NotNull State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State state) {
        this.state = state;
    }
}
