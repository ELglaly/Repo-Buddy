package com.repoinspector.settings;

import com.repoinspector.runner.startup.RepoBuddyAgentArguments;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RepoBuddyAgentArgumentsTest {
    @Test void recognizesOnlyRepoBuddyAgentNamesAcrossPlatforms() {
        assertTrue(RepoBuddyAgentArguments.isRepoBuddyAgentArgument("-javaagent:/tmp/repobuddy-agent.jar"));
        assertTrue(RepoBuddyAgentArguments.isRepoBuddyAgentArgument("-javaagent:\"C:\\Program Files\\RepoBuddy\\repobuddy-agent-1.0.6.jar\""));
        assertTrue(RepoBuddyAgentArguments.isRepoBuddyAgentArgument("-javaagent:/Users/a/repobuddy-agent-deadbeefcafebabe.jar=port=1"));
        assertTrue(RepoBuddyAgentArguments.isRepoBuddyAgentArgument("-javaagent:\"C:\\Program Files\\RepoBuddy\\repobuddy-agent.jar\"=port=1"));
        assertFalse(RepoBuddyAgentArguments.isRepoBuddyAgentArgument("-javaagent:/opt/jacoco/jacocoagent.jar"));
        assertFalse(RepoBuddyAgentArguments.isRepoBuddyAgentArgument("-javaagent:/opt/otel/opentelemetry-javaagent.jar"));
        assertFalse(RepoBuddyAgentArguments.isRepoBuddyAgentArgument("-javaagent:/tools/custom-repo-agent.jar"));
    }

    @Test void cleanupPreservesThirdPartyAgentsAndQuotedValues() {
        String options = "-Xmx2g -javaagent:\"C:\\Users\\John Doe\\repobuddy-agent.jar\" -Dfoo=bar -javaagent:/opt/jacoco/jacocoagent.jar";
        assertEquals("-Xmx2g -Dfoo=bar -javaagent:/opt/jacoco/jacocoagent.jar", RepoBuddyAgentArguments.removeRepoBuddyAgentArguments(options));
    }

    @Test void cleanupRemovesDuplicatesRegardlessOfPosition() {
        String options = "-javaagent:/tmp/repobuddy-agent-12345678.jar -Dfoo=bar -javaagent:C:\\plugins\\repobuddy-agent.jar -javaagent:/tools/custom-agent.jar";
        assertEquals("-Dfoo=bar -javaagent:/tools/custom-agent.jar", RepoBuddyAgentArguments.removeRepoBuddyAgentArguments(options));
    }
}
