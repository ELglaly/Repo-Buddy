package com.repoinspector.integration;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import com.repoinspector.settings.RepoBuddySettings;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Registers an open project with the opt-in local integration server. */
public final class RepoBuddyLocalIntegrationStartupActivity implements ProjectActivity {
    @Override
    public @Nullable Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        if (RepoBuddySettings.getInstance().isLocalIntegrationEnabled()) {
            RepoBuddyLocalApiServer server = RepoBuddyLocalApiServer.getInstance();
            server.enable();
            server.register(project);
        }
        return Unit.INSTANCE;
    }
}
