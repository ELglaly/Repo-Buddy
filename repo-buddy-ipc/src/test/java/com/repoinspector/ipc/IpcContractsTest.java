package com.repoinspector.ipc;

import com.google.gson.JsonObject;
import com.repoinspector.core.RepoBuddyJson;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IpcContractsTest {
    @Test void requestRoundTripsWithExplicitProtocolVersion() {
        IpcRequest request = new IpcRequest("1", "scan", "project-1", new JsonObject());
        IpcRequest decoded = RepoBuddyJson.gson(false).fromJson(RepoBuddyJson.gson(false).toJson(request), IpcRequest.class);
        assertEquals("1", decoded.protocolVersion());
        assertEquals("scan", decoded.action());
        assertEquals("project-1", decoded.projectId());
    }

    @Test void descriptorDoesNotLeakTokenFromProjectFields() {
        SessionDescriptor descriptor = new SessionDescriptor("1", 1234, 99, "p-1", "project", "C:\\work", "token");
        assertEquals("token", descriptor.token());
        assertFalse(descriptor.projectId().contains(descriptor.token()));
    }

    @Test void projectSelectorAcceptsStableIdNameAndRoot() {
        SessionDescriptor descriptor = new SessionDescriptor("1", 1234, 99, "D2L-8b6b2474", "D2L",
                "H:\\D2L", "token");
        assertTrue(SessionDiscovery.matches(descriptor, "D2L-8b6b2474"));
        assertTrue(SessionDiscovery.matches(descriptor, "D2L"));
        assertTrue(SessionDiscovery.matches(descriptor, "H:\\D2L"));
        assertFalse(SessionDiscovery.matches(descriptor, "other"));
    }

    @Test void duplicateSessionsForTheSameLogicalProjectAreCollapsed() {
        SessionDescriptor older = new SessionDescriptor("1", 1234, 10, "D2L-8b6b2474", "D2L",
                "H:\\D2L", "older-token");
        SessionDescriptor newer = new SessionDescriptor("1", 5678, 20, "D2L-8b6b2474", "D2L",
                "H:\\D2L", "newer-token");
        SessionDescriptor other = new SessionDescriptor("1", 9012, 30, "other-12345678", "other",
                "H:\\other", "other-token");

        List<SessionDescriptor> projects = SessionDiscovery.distinctProjects(List.of(older, newer, other));

        assertEquals(2, projects.size());
        assertEquals("newer-token", projects.get(0).token());
        assertEquals("other-12345678", projects.get(1).projectId());
    }
}
