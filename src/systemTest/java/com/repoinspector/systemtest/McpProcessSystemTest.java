package com.repoinspector.systemtest;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class McpProcessSystemTest {
    @TempDir Path temporaryDirectory;

    @Test void packagedMcpSupportsInitializationToolsAndRecoversAfterAnError() {
        try (var mcp = SystemTestSupport.startMcp(temporaryDirectory)) {
            JsonObject initialized = mcp.request(request(1, "initialize"));
            assertEquals("2.0", initialized.get("jsonrpc").getAsString());
            assertEquals("2025-06-18", initialized.getAsJsonObject("result").get("protocolVersion").getAsString());
            JsonObject tools = mcp.request(request(2, "tools/list"));
            String listed = tools.toString();
            for (String name : new String[]{"repobuddy_list_projects", "repobuddy_scan_project", "repobuddy_list_issues", "repobuddy_get_issue", "repobuddy_get_issue_details", "repobuddy_get_issue_context", "repobuddy_get_project_info", "repobuddy_get_rules", "repobuddy_get_rule_details", "repobuddy_get_issues", "repobuddy_check_changes"}) assertTrue(listed.contains(name));
            JsonObject unknown = mcp.request(request(3, "unknown/method"));
            assertEquals(-32601, unknown.getAsJsonObject("error").get("code").getAsInt());
            JsonObject again = mcp.request(request(4, "tools/list"));
            assertTrue(again.has("result"));
        }
    }

    @Test void listProjectsIsAvailableWithoutAnIdeSession() {
        try (var mcp = SystemTestSupport.startMcp(temporaryDirectory)) {
            mcp.request(request(1, "initialize"));
            JsonObject request = request(2, "tools/call"); JsonObject params = new JsonObject(); params.addProperty("name", "repobuddy_list_projects"); params.add("arguments", new JsonObject()); request.add("params", params);
            JsonObject response = mcp.request(request);
            assertFalse(response.getAsJsonObject("result").get("isError").getAsBoolean());
        }
    }

    private static JsonObject request(int id, String method) { JsonObject request = new JsonObject(); request.addProperty("jsonrpc", "2.0"); request.addProperty("id", id); request.addProperty("method", method); return request; }
}
