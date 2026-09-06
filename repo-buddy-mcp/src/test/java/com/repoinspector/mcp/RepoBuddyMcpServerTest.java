package com.repoinspector.mcp;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RepoBuddyMcpServerTest {
    @Test void initializesAndPublishesOnlyReadOnlyTools() {
        RepoBuddyMcpServer server = new RepoBuddyMcpServer();
        JsonObject initialize = request(1, "initialize");
        assertEquals("2025-06-18", server.handle(initialize).getAsJsonObject("result").get("protocolVersion").getAsString());

        JsonObject tools = server.handle(request(2, "tools/list")).getAsJsonObject("result");
        String text = tools.toString();
        assertTrue(text.contains("repobuddy_scan_project"));
        assertTrue(text.contains("repobuddy_get_issue_context"));
        assertTrue(text.contains("repobuddy_list_projects"));
        assertTrue(text.contains("next page"));
        assertTrue(text.contains("projectId"));
        assertTrue(text.contains("repobuddy_get_issues"));
        assertTrue(text.contains("repobuddy_check_changes"));
        assertTrue(text.contains("scope is required"));
        assertFalse(text.contains("write-file"));
        assertFalse(text.contains("shell"));
    }

    @Test void changedIssueToolRequiresAnExplicitScope() {
        JsonObject request = request(7, "tools/call");
        JsonObject params = new JsonObject(); params.addProperty("name", "repobuddy_get_issues");
        params.add("arguments", new JsonObject()); request.add("params", params);
        JsonObject result = new RepoBuddyMcpServer().handle(request).getAsJsonObject("result");
        assertTrue(result.get("isError").getAsBoolean());
        assertTrue(result.toString().contains("scope is required"));
    }

    @Test void unknownMethodUsesJsonRpcError() {
        JsonObject response = new RepoBuddyMcpServer().handle(request(3, "unknown"));
        assertEquals(-32601, response.getAsJsonObject("error").get("code").getAsInt());
    }

    @Test void toolRejectsArgumentsOutsideItsSchema() {
        JsonObject request = request(4, "tools/call");
        JsonObject params = new JsonObject(); params.addProperty("name", "repobuddy_get_project_info");
        JsonObject arguments = new JsonObject(); arguments.addProperty("path", "../../secret");
        params.add("arguments", arguments); request.add("params", params);
        JsonObject result = new RepoBuddyMcpServer().handle(request).getAsJsonObject("result");
        assertTrue(result.get("isError").getAsBoolean());
        assertTrue(result.toString().contains("Unexpected argument"));
    }

    @Test void issuePagesRejectMoreThanTheMcpBound() {
        JsonObject request = request(5, "tools/call");
        JsonObject params = new JsonObject(); params.addProperty("name", "repobuddy_list_issues");
        JsonObject arguments = new JsonObject(); arguments.addProperty("limit", 26);
        params.add("arguments", arguments); request.add("params", params);
        JsonObject result = new RepoBuddyMcpServer().handle(request).getAsJsonObject("result");
        assertTrue(result.get("isError").getAsBoolean());
        assertTrue(result.toString().contains("between 1 and 25"));
    }

    @Test void projectListingDoesNotRequireAnIntellijConnection() {
        JsonObject request = request(6, "tools/call");
        JsonObject params = new JsonObject(); params.addProperty("name", "repobuddy_list_projects");
        params.add("arguments", new JsonObject()); request.add("params", params);
        JsonObject result = new RepoBuddyMcpServer().handle(request).getAsJsonObject("result");
        assertFalse(result.get("isError").getAsBoolean());
        assertTrue(result.has("structuredContent"));
    }

    private static JsonObject request(int id, String method) {
        JsonObject request = new JsonObject(); request.addProperty("jsonrpc", "2.0");
        request.addProperty("id", id); request.addProperty("method", method); return request;
    }
}
