package com.repoinspector.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.repoinspector.core.IssuePage;
import com.repoinspector.core.RepoBuddyIssue;
import com.repoinspector.core.RepoBuddyRule;
import com.repoinspector.core.RepoBuddyRules;
import com.repoinspector.core.Severity;
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
        assertTrue(text.contains("repobuddy_get_issue_details"));
        assertTrue(text.contains("repobuddy_get_rule_details"));
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

    @Test void rulePagesRejectInvalidPaginationBeforeConnectingToIntellij() {
        JsonObject request = request(51, "tools/call");
        JsonObject params = new JsonObject(); params.addProperty("name", "repobuddy_get_rules");
        JsonObject arguments = new JsonObject(); arguments.addProperty("limit", 0); arguments.addProperty("offset", -1);
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

    @Test void issuePagesReturnOnlyIdsAndStayWithinTheMcpResponseBudget() {
        RepoBuddyIssue issue = new RepoBuddyIssue("RB-TEST-000000000001", "unsafe-query", "DATABASE_SECURITY",
                Severity.HIGH, "src/main/java/com/example/Demo.java", 12, 4, "Unsafe query ".repeat(30), "Explanation ".repeat(30),
                "Recommendation ".repeat(30), "Affected symbol ".repeat(30), null, null);
        IssuePage page = new IssuePage("1", "scan-123", 25, 25, 0, null, false,
                java.util.stream.IntStream.range(0, 25).mapToObj(index -> new RepoBuddyIssue(
                        "RB-TEST-" + String.format("%012d", index), issue.ruleId(), issue.category(), issue.severity(), issue.path(),
                        issue.line(), issue.column(), issue.message(), issue.explanation(), issue.suggestedFix(), issue.affectedSymbol(), null, null)).toList());

        JsonObject response = RepoBuddyMcpServer.responseFor(page);

        assertFalse(response.get("isError").getAsBoolean());
        assertTrue(response.has("content"));
        assertTrue(response.has("structuredContent"));
        String text = response.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
        assertTrue(text.contains("25 issues"));
        JsonObject structured = response.getAsJsonObject("structuredContent");
        JsonArray issueIds = structured.getAsJsonArray("issueIds");
        assertEquals(25, issueIds.size());
        assertFalse(structured.has("issues"));
        assertTrue(RepoBuddyMcpServer.serializedResponse(new com.google.gson.JsonPrimitive(8),
                successResponse(response)).getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 1024);
    }

    @Test void oversizedMcpResponsesReturnAnExplicitToolError() {
        JsonObject response = new JsonObject(); response.addProperty("large", "x".repeat(2048));

        String serialized = RepoBuddyMcpServer.serializedResponse(new com.google.gson.JsonPrimitive(9), response);

        assertTrue(serialized.contains("REPOBUDDY_OUTPUT_LIMIT_EXCEEDED"));
    }

    @Test void issueSummaryOmitsVerboseGuidanceWhileDetailsAreBounded() {
        RepoBuddyIssue issue = new RepoBuddyIssue("RB-TEST-000000000001", "unsafe-query", "DATABASE_SECURITY",
                Severity.HIGH, "src/main/java/com/example/Demo.java", 12, 4, "Unsafe query", "é".repeat(800),
                "Remediation ".repeat(100), "DemoRepository.findByName", null, null);

        JsonObject summary = RepoBuddyMcpServer.responseFor(issue).getAsJsonObject("structuredContent");
        assertTrue(summary.has("message"));
        assertEquals("12:4", summary.get("location").getAsString());
        assertFalse(summary.has("explanation"));
        assertFalse(summary.has("remediation"));

        JsonObject details = RepoBuddyMcpServer.responseFor(RepoBuddyMcpServer.issueDetails(issue)).getAsJsonObject("structuredContent");
        assertEquals("RB-TEST-000000000001", details.get("issueId").getAsString());
        assertTrue(details.get("truncated").getAsBoolean());
        assertTrue(RepoBuddyMcpServer.serializedResponse(new com.google.gson.JsonPrimitive(10),
                successResponse(RepoBuddyMcpServer.responseFor(RepoBuddyMcpServer.issueDetails(issue))))
                .getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 1024);
    }

    @Test void compactRulePagesPaginateAndRuleDetailsKeepGuidanceSeparate() {
        JsonObject page = RepoBuddyMcpServer.responseFor(RepoBuddyMcpServer.rulePage(RepoBuddyRules.all(), 2, 1))
                .getAsJsonObject("structuredContent");
        assertEquals(RepoBuddyRules.all().size(), page.get("total").getAsInt());
        assertEquals(2, page.get("limit").getAsInt());
        assertEquals(1, page.get("offset").getAsInt());
        assertEquals(2, page.getAsJsonArray("rules").size());
        assertFalse(page.toString().contains("description"));
        assertFalse(page.toString().contains("recommendation"));
        assertTrue(RepoBuddyMcpServer.serializedResponse(new com.google.gson.JsonPrimitive(12),
                successResponse(RepoBuddyMcpServer.responseFor(RepoBuddyMcpServer.rulePage(RepoBuddyRules.all(), 25, 0))))
                .getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 1024);

        RepoBuddyRule verbose = new RepoBuddyRule("verbose", "VERBOSE", "Verbose rule", "TEST", Severity.INFO, true,
                "Description ".repeat(100), "Recommendation ".repeat(100), java.util.List.of("False positive ".repeat(50)));
        JsonObject details = RepoBuddyMcpServer.responseFor(RepoBuddyMcpServer.ruleDetails(verbose))
                .getAsJsonObject("structuredContent");
        assertTrue(details.get("truncated").getAsBoolean());
        assertTrue(details.has("falsePositiveGuidance"));
        assertTrue(RepoBuddyMcpServer.serializedResponse(new com.google.gson.JsonPrimitive(11),
                successResponse(RepoBuddyMcpServer.responseFor(RepoBuddyMcpServer.ruleDetails(verbose))))
                .getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 1024);
    }

    private static JsonObject successResponse(JsonObject result) {
        JsonObject response = new JsonObject(); response.addProperty("jsonrpc", "2.0"); response.addProperty("id", 8);
        response.add("result", result); return response;
    }

    private static JsonObject request(int id, String method) {
        JsonObject request = new JsonObject(); request.addProperty("jsonrpc", "2.0");
        request.addProperty("id", id); request.addProperty("method", method); return request;
    }
}
