package com.repoinspector.runner.startup;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;

/** Removes legacy persisted RepoBuddy entries; repeated execution is harmless. */
public final class RepoBuddyAgentMigrationActivity implements ProjectActivity, DumbAware {
    private static final Logger LOG = Logger.getInstance(RepoBuddyAgentMigrationActivity.class);
    @Override public Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        int removed = AgentConfigCleaner.removeAgentFromConfigurations(project);
        if (removed > 0) LOG.info("RepoBuddy: legacy Java agent migration cleaned " + removed + " run configuration(s)");
        return Unit.INSTANCE;
    }
}
