package com.repoinspector.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.repoinspector.core.*;
import com.repoinspector.ipc.RepoBuddyIpcClient;
import com.repoinspector.ipc.SessionDiscovery;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Minimal read-only MCP stdio adapter over RepoBuddyApplicationService. */
public final class RepoBuddyMcpServer {
    private static final int MAX_MESSAGE_CHARS = 64 * 1024;

    public static void main(String[] args) throws Exception {
        new RepoBuddyMcpServer().run();
    }

    public void run() throws Exception {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonObject request;
                try {
                    if (line.length() > MAX_MESSAGE_CHARS) throw new IllegalArgumentException("MCP request exceeds 64 KB");
                    request = JsonParser.parseString(line).getAsJsonObject();
                    JsonObject response = handle(request);
                    if (response != null) System.out.println(RepoBuddyJson.gson(false).toJson(response));
                } catch (RuntimeException error) {
                    System.out.println(RepoBuddyJson.gson(false).toJson(error(null, -32600, "Invalid request")));
                }
                System.out.flush();
            }
        }
    }

    JsonObject handle(JsonObject request) {
        JsonElement id = request.get("id");
        String method = request.has("method") ? request.get("method").getAsString() : "";
        if (method.startsWith("notifications/")) return null;
        try {
            return switch (method) {
                case "initialize" -> success(id, initialize());
                case "ping" -> success(id, new JsonObject());
                case "tools/list" -> success(id, tools());
                case "tools/call" -> success(id, call(request.getAsJsonObject("params")));
                default -> error(id, -32601, "Method not found");
            };
        } catch (Throwable failure) {
            Throwable cause = failure;
            while (cause.getCause() != null && (cause instanceof java.util.concurrent.ExecutionException
                    || cause instanceof java.util.concurrent.CompletionException)) cause = cause.getCause();
            String message = cause instanceof RepoBuddyException repo ? repo.code() + ": " + repo.getMessage()
                    : "REPOBUDDY_INTERNAL_ERROR: RepoBuddy tool failed";
            return success(id, toolError(message));
        }
    }

    private JsonObject call(JsonObject params) throws Exception {
        if (params == null || !params.has("name")) throw new RepoBuddyException(RepoBuddyErrorCode.REPOBUDDY_INVALID_ARGUMENT, "Tool name is required");
        String name = params.get("name").getAsString();
        JsonObject arguments = params.has("arguments") ? params.getAsJsonObject("arguments") : new JsonObject();
        validateArguments(name, arguments);
        if (name.equals("repobuddy_list_projects")) return projectListResponse();
        String project = selector(arguments);
        RepoBuddyApplicationService service = new RepoBuddyIpcClient(SessionDiscovery.select(Path.of("."), project), Duration.ofSeconds(60));
        Object result = switch (name) {
            case "repobuddy_scan_project" -> await(service.scan(new ScanRequest(severity(arguments, "severity"), string(arguments, "rule"))));
            case "repobuddy_list_issues" -> await(service.listIssues(new IssueQuery(severity(arguments, "severity"),
                    severity(arguments, "minimumSeverity"), string(arguments, "rule"), string(arguments, "file"),
                    integer(arguments, "limit", 25), integer(arguments, "offset", 0), string(arguments, "scanId"))));
            case "repobuddy_get_issue" -> await(service.getIssue(required(arguments, "issueId")));
            case "repobuddy_get_issue_context" -> await(service.getIssueContext(required(arguments, "issueId"), integer(arguments, "lines", 10)));
            case "repobuddy_get_project_info" -> await(service.getProjectInfo());
            case "repobuddy_get_rules" -> await(service.getRules(new RuleQuery(arguments.has("enabled") ? arguments.get("enabled").getAsBoolean() : null, string(arguments, "rule"))));
            case "repobuddy_get_issues" -> await(service.getIssues(new ScopedIssueRequest(
                    AnalysisScope.parse(required(arguments, "scope")), severity(arguments, "severity"),
                    severity(arguments, "minimumSeverity"), string(arguments, "rule"), string(arguments, "file"),
                    integer(arguments, "limit", 25), integer(arguments, "offset", 0), string(arguments, "scanId"))));
            case "repobuddy_check_changes" -> await(service.checkChanges(severity(arguments, "severity"), string(arguments, "rule")));
            default -> throw new RepoBuddyException(RepoBuddyErrorCode.REPOBUDDY_INVALID_ARGUMENT, "Unknown RepoBuddy tool: " + name);
        };
        JsonElement structured = RepoBuddyJson.gson(false).toJsonTree(result);
        JsonObject response = new JsonObject();
        JsonArray content = new JsonArray();
        JsonObject text = new JsonObject();
        text.addProperty("type", "text");
        text.addProperty("text", RepoBuddyJson.gson(false).toJson(result));
        content.add(text);
        response.add("content", content);
        response.add("structuredContent", structured);
        response.addProperty("isError", false);
        return response;
    }

    private static Object await(java.util.concurrent.CompletionStage<?> value) throws Exception {
        return value.toCompletableFuture().get(30, TimeUnit.SECONDS);
    }

    private static String selector(JsonObject arguments) {
        String id = string(arguments, "projectId");
        String legacy = string(arguments, "project");
        if (id != null && legacy != null && !id.equals(legacy)) throw new RepoBuddyException(
                RepoBuddyErrorCode.REPOBUDDY_INVALID_ARGUMENT, "Use only projectId; it must be the id returned by repobuddy_list_projects");
        return id != null ? id : legacy;
    }

    private static JsonObject projectListResponse() {
        JsonArray projects = new JsonArray();
        SessionDiscovery.readAll().stream()
                .filter(value -> ProcessHandle.of(value.pid()).map(ProcessHandle::isAlive).orElse(false))
                .map(value -> new RepoBuddyProjectRef(value.projectId(), value.projectName(), value.projectRoot(), true))
                .forEach(value -> projects.add(RepoBuddyJson.gson(false).toJsonTree(value)));
        JsonObject structured = new JsonObject(); structured.add("projects", projects);
        JsonObject response = new JsonObject();
        response.add("content", textContent(RepoBuddyJson.gson(false).toJson(projects)));
        response.add("structuredContent", structured);
        response.addProperty("isError", false);
        return response;
    }

    private static JsonArray textContent(String value) {
        JsonArray content = new JsonArray(); JsonObject text = new JsonObject();
        text.addProperty("type", "text"); text.addProperty("text", value); content.add(text); return content;
    }

    private static void validateArguments(String tool, JsonObject arguments) {
        List<String> allowed = switch (tool) {
            case "repobuddy_list_projects" -> List.of();
            case "repobuddy_scan_project" -> List.of("severity", "rule", "projectId", "project");
            case "repobuddy_list_issues" -> List.of("severity", "minimumSeverity", "rule", "file", "limit", "offset", "scanId", "projectId", "project");
            case "repobuddy_get_issue" -> List.of("issueId", "projectId", "project");
            case "repobuddy_get_issue_context" -> List.of("issueId", "lines", "projectId", "project");
            case "repobuddy_get_project_info" -> List.of("projectId", "project");
            case "repobuddy_get_rules" -> List.of("enabled", "rule", "projectId", "project");
            case "repobuddy_get_issues" -> List.of("scope", "severity", "minimumSeverity", "rule", "file", "limit", "offset", "scanId", "projectId", "project");
            case "repobuddy_check_changes" -> List.of("severity", "rule", "projectId", "project");
            default -> throw new RepoBuddyException(RepoBuddyErrorCode.REPOBUDDY_INVALID_ARGUMENT,
                    "Unknown RepoBuddy tool: " + tool);
        };
        for (String key : arguments.keySet()) if (!allowed.contains(key)) throw new RepoBuddyException(
                RepoBuddyErrorCode.REPOBUDDY_INVALID_ARGUMENT, "Unexpected argument: " + key);
        if (tool.equals("repobuddy_get_issues") && (!arguments.has("scope") || arguments.get("scope").getAsString().isBlank()))
            throw new RepoBuddyException(RepoBuddyErrorCode.REPOBUDDY_INVALID_ARGUMENT, "scope is required");
        if (arguments.has("limit")) {
            int limit = integer(arguments, "limit", 25);
            if (limit < 1 || limit > 25) throw new RepoBuddyException(RepoBuddyErrorCode.REPOBUDDY_INVALID_ARGUMENT,
                    "MCP issue pages must use limit between 1 and 25; request the next page with offset");
        }
        if (arguments.has("offset") && integer(arguments, "offset", 0) < 0)
            throw new RepoBuddyException(RepoBuddyErrorCode.REPOBUDDY_INVALID_ARGUMENT, "offset must be non-negative");
    }

    private static JsonObject initialize() {
        JsonObject value = new JsonObject();
        value.addProperty("protocolVersion", "2025-06-18");
        JsonObject capabilities = new JsonObject();
        JsonObject tools = new JsonObject(); tools.addProperty("listChanged", false);
        capabilities.add("tools", tools); value.add("capabilities", capabilities);
        JsonObject info = new JsonObject(); info.addProperty("name", "repobuddy"); info.addProperty("version", RepoBuddyVersion.current());
        value.add("serverInfo", info);
        return value;
    }

    private static JsonObject tools() {
        JsonArray tools = new JsonArray();
        tools.add(tool("repobuddy_list_projects", "List active RepoBuddy IntelliJ projects. Use the returned id as projectId for other tools.", schema()));
        tools.add(tool("repobuddy_scan_project", "Run RepoBuddy inspections. Omit projectId only when the current directory uniquely identifies one open project.", schema("severity", "rule", "projectId", "project")));
        tools.add(tool("repobuddy_list_issues", "List one bounded page of findings. Use the scanId from scan and request the next page with offset while hasMore is true. MCP pages are limited to 25 issues.", schema("severity", "minimumSeverity", "rule", "file", "limit", "offset", "scanId", "projectId", "project")));
        tools.add(tool("repobuddy_get_issue", "Get one normalized finding by issueId", requiredSchema("issueId", "projectId", "project")));
        tools.add(tool("repobuddy_get_issue_context", "Get bounded source context for a known finding; lines is limited to 50", requiredSchema("issueId", "lines", "projectId", "project")));
        tools.add(tool("repobuddy_get_project_info", "Describe the selected project; use projectId from repobuddy_list_projects", schema("projectId", "project")));
        tools.add(tool("repobuddy_get_rules", "List RepoBuddy rule metadata", schema("enabled", "rule", "projectId", "project")));
        tools.add(tool("repobuddy_get_issues", "Run a fresh all-or-changed analysis and return one bounded page. scope is required: all or changed.",
                requiredSchema("scope", "severity", "minimumSeverity", "rule", "file", "limit", "offset", "scanId", "projectId", "project")));
        tools.add(tool("repobuddy_check_changes", "Verify that current Git changes introduce no RepoBuddy issues. Returns at most 25 issue details and a scanId.",
                schema("severity", "rule", "projectId", "project")));
        JsonObject result = new JsonObject(); result.add("tools", tools); return result;
    }

    private static JsonObject tool(String name, String description, JsonObject schema) {
        JsonObject tool = new JsonObject(); tool.addProperty("name", name); tool.addProperty("description", description); tool.add("inputSchema", schema); return tool;
    }

    private static JsonObject schema(String... properties) {
        JsonObject schema = new JsonObject(); schema.addProperty("type", "object"); schema.addProperty("additionalProperties", false);
        JsonObject values = new JsonObject();
        for (String property : properties) {
            JsonObject definition = new JsonObject();
            definition.addProperty("type", switch (property) { case "limit", "offset", "lines" -> "integer"; case "enabled" -> "boolean"; default -> "string"; });
            if (property.equals("limit")) { definition.addProperty("minimum", 1); definition.addProperty("maximum", 25); definition.addProperty("default", 25); }
            if (property.equals("offset")) definition.addProperty("minimum", 0);
            if (property.equals("lines")) { definition.addProperty("minimum", 0); definition.addProperty("maximum", 50); }
            if (property.equals("severity") || property.equals("minimumSeverity")) {
                JsonArray valuesArray = new JsonArray();
                for (Severity value : Severity.values()) valuesArray.add(value.name());
                definition.add("enum", valuesArray);
            }
            if (property.equals("issueId")) definition.addProperty("pattern", "^RB-[A-Z0-9]+-[0-9a-f]{12}$");
            if (property.equals("scope")) {
                JsonArray valuesArray = new JsonArray(); valuesArray.add("all"); valuesArray.add("changed");
                definition.add("enum", valuesArray);
            }
            if (property.equals("projectId")) definition.addProperty("description", "Stable id returned by repobuddy_list_projects");
            values.add(property, definition);
        }
        schema.add("properties", values); return schema;
    }

    private static JsonObject requiredSchema(String required, String... other) {
        String[] all = new String[other.length + 1]; all[0] = required; System.arraycopy(other, 0, all, 1, other.length);
        JsonObject schema = schema(all); JsonArray requiredValues = new JsonArray(); requiredValues.add(required); schema.add("required", requiredValues); return schema;
    }

    private static JsonObject toolError(String message) {
        JsonObject result = new JsonObject(); JsonArray content = new JsonArray(); JsonObject text = new JsonObject();
        text.addProperty("type", "text"); text.addProperty("text", message); content.add(text);
        result.add("content", content); result.addProperty("isError", true); return result;
    }

    private static JsonObject success(JsonElement id, JsonObject result) {
        JsonObject response = base(id); response.add("result", result); return response;
    }
    private static JsonObject error(JsonElement id, int code, String message) {
        JsonObject response = base(id); JsonObject error = new JsonObject(); error.addProperty("code", code); error.addProperty("message", message); response.add("error", error); return response;
    }
    private static JsonObject base(JsonElement id) { JsonObject value = new JsonObject(); value.addProperty("jsonrpc", "2.0"); if (id != null) value.add("id", id); return value; }
    private static String required(JsonObject value, String name) { String result = string(value, name); if (result == null || result.isBlank()) throw new RepoBuddyException(RepoBuddyErrorCode.REPOBUDDY_INVALID_ARGUMENT, name + " is required"); return result; }
    private static String string(JsonObject value, String name) { return value.has(name) && !value.get(name).isJsonNull() ? value.get(name).getAsString() : null; }
    private static int integer(JsonObject value, String name, int fallback) {
        try { return value.has(name) ? value.get(name).getAsInt() : fallback; }
        catch (RuntimeException error) { throw new RepoBuddyException(RepoBuddyErrorCode.REPOBUDDY_INVALID_ARGUMENT, name + " must be an integer"); }
    }
    private static Severity severity(JsonObject value, String name) {
        String raw = string(value, name);
        if (raw == null) return null;
        try { return Severity.valueOf(raw.toUpperCase()); }
        catch (IllegalArgumentException error) { throw new RepoBuddyException(RepoBuddyErrorCode.REPOBUDDY_INVALID_ARGUMENT,
                name + " must be one of HIGH, MEDIUM, LOW, or INFO"); }
    }
}
