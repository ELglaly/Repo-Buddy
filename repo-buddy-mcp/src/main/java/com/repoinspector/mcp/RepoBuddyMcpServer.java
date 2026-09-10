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
    // Codex can stall while consuming large stdio tool results. Keep every response below this budget.
    private static final int MAX_RESPONSE_BYTES = 1024;
    private static final int SUMMARY_TEXT_BYTES = 160;
    private static final int SYMBOL_TEXT_BYTES = 96;
    private static final int DETAIL_TEXT_BYTES = 260;
    private static final int RULE_DETAIL_TEXT_BYTES = 220;
    private static final int CONTEXT_TEXT_BYTES = 420;

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
                    if (response != null) {
                        // Tool discovery is protocol metadata, not a data-bearing tool result. It
                        // necessarily contains every schema and cannot be made useful under the
                        // 1 KB result budget that protects issue/context responses.
                        boolean toolDiscovery = "tools/list".equals(request.has("method") ? request.get("method").getAsString() : "");
                        System.out.println(toolDiscovery ? RepoBuddyJson.gson(false).toJson(response)
                                : serializedResponse(request.get("id"), response));
                    }
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
            case "repobuddy_get_issue_details" -> await(service.getIssue(required(arguments, "issueId")));
            case "repobuddy_get_issue_context" -> await(service.getIssueContext(required(arguments, "issueId"), integer(arguments, "lines", 10)));
            case "repobuddy_get_project_info" -> await(service.getProjectInfo());
            case "repobuddy_get_rules" -> await(service.getRules(new RuleQuery(arguments.has("enabled") ? arguments.get("enabled").getAsBoolean() : null, string(arguments, "rule"))));
            case "repobuddy_get_rule_details" -> await(service.getRules(new RuleQuery(null, required(arguments, "ruleId"))));
            case "repobuddy_get_issues" -> await(service.getIssues(new ScopedIssueRequest(
                    AnalysisScope.parse(required(arguments, "scope")), severity(arguments, "severity"),
                    severity(arguments, "minimumSeverity"), string(arguments, "rule"), string(arguments, "file"),
                    integer(arguments, "limit", 25), integer(arguments, "offset", 0), string(arguments, "scanId"))));
            case "repobuddy_check_changes" -> await(service.checkChanges(severity(arguments, "severity"), string(arguments, "rule")));
            default -> throw new RepoBuddyException(RepoBuddyErrorCode.REPOBUDDY_INVALID_ARGUMENT, "Unknown RepoBuddy tool: " + name);
        };
        if (name.equals("repobuddy_get_issue_details")) return responseFor(issueDetails((RepoBuddyIssue) result));
        if (name.equals("repobuddy_get_rule_details")) {
            List<RepoBuddyRule> rules = rules(result);
            if (rules.isEmpty()) throw new RepoBuddyException(RepoBuddyErrorCode.REPOBUDDY_INVALID_ARGUMENT,
                    "Unknown RepoBuddy rule: " + required(arguments, "ruleId"));
            return responseFor(ruleDetails(rules.get(0)));
        }
        if (name.equals("repobuddy_get_rules")) return responseFor(rulePage(rules(result),
                integer(arguments, "limit", 25), integer(arguments, "offset", 0)));
        return responseFor(result);
    }

    private static Object await(java.util.concurrent.CompletionStage<?> value) throws Exception {
        return value.toCompletableFuture().get(30, TimeUnit.SECONDS);
    }

    private static List<RepoBuddyRule> rules(Object result) {
        if (!(result instanceof List<?> list)) throw new IllegalStateException("Expected RepoBuddy rules");
        return list.stream().map(RepoBuddyRule.class::cast).toList();
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
        SessionDiscovery.readProjects().stream()
                .map(value -> new RepoBuddyProjectRef(value.projectId(), value.projectName(), value.projectRoot(), true))
                .forEach(value -> projects.add(RepoBuddyJson.gson(false).toJsonTree(value)));
        JsonObject structured = new JsonObject();
        structured.add("projects", projects);
        JsonObject response = new JsonObject();
        response.add("content", textContent(projects.size() + (projects.size() == 1 ? " active project" : " active projects")));
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
            case "repobuddy_get_issue_details" -> List.of("issueId", "projectId", "project");
            case "repobuddy_get_issue_context" -> List.of("issueId", "lines", "projectId", "project");
            case "repobuddy_get_project_info" -> List.of("projectId", "project");
            case "repobuddy_get_rules" -> List.of("enabled", "rule", "limit", "offset", "projectId", "project");
            case "repobuddy_get_rule_details" -> List.of("ruleId", "projectId", "project");
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
        tools.add(tool("repobuddy_list_issues", "List issue IDs and pagination metadata. Use repobuddy_get_issue for details, then request the next page with offset while hasMore is true. MCP pages are limited to 25 issue IDs.", schema("severity", "minimumSeverity", "rule", "file", "limit", "offset", "scanId", "projectId", "project")));
        tools.add(tool("repobuddy_get_issue", "Get a compact issue summary: identity, location, message, affected symbol, and change status. Use repobuddy_get_issue_details for explanation and remediation.", requiredSchema("issueId", "projectId", "project")));
        tools.add(tool("repobuddy_get_issue_details", "Get bounded explanation and remediation for one issue. truncated is true when text was shortened; use repobuddy_get_issue_context for source excerpt.", requiredSchema("issueId", "projectId", "project")));
        tools.add(tool("repobuddy_get_issue_context", "Get bounded source context for a known finding; lines is limited to 50", requiredSchema("issueId", "lines", "projectId", "project")));
        tools.add(tool("repobuddy_get_project_info", "Describe the selected project; use projectId from repobuddy_list_projects", schema("projectId", "project")));
        tools.add(tool("repobuddy_get_rules", "List compact RepoBuddy rule metadata with pagination. Use repobuddy_get_rule_details for description and guidance.", schema("enabled", "rule", "limit", "offset", "projectId", "project")));
        tools.add(tool("repobuddy_get_rule_details", "Get bounded description, recommendation, and false-positive guidance for one rule. truncated is true when text was shortened.", requiredSchema("ruleId", "projectId", "project")));
        tools.add(tool("repobuddy_get_issues", "Run a fresh all-or-changed analysis and return issue IDs plus pagination metadata. Use repobuddy_get_issue for details. scope is required: all or changed.",
                requiredSchema("scope", "severity", "minimumSeverity", "rule", "file", "limit", "offset", "scanId", "projectId", "project")));
        tools.add(tool("repobuddy_check_changes", "Verify that current Git changes introduce no RepoBuddy issues. Returns at most 25 issue IDs and a scanId; use repobuddy_get_issue for details.",
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

    static JsonObject responseFor(Object result) {
        JsonObject response = new JsonObject();
        response.add("content", textContent(summary(result)));
        response.add("structuredContent", RepoBuddyJson.gson(false).toJsonTree(compactResult(result)));
        response.addProperty("isError", false);
        return response;
    }

    private static Object compactResult(Object result) {
        if (result instanceof IssuePage page) return new McpIssuePage("2", page.scanId(), page.total(), page.limit(), page.offset(),
                page.nextOffset(), page.hasMore(), issueIds(page.issues()));
        if (result instanceof ScopedIssuePage page) return new McpScopedIssuePage("2", page.scanId(), page.scope(), page.baseline(),
                page.availability(), page.message(), page.total(), page.preExistingIssueCount(), page.resolvedIssueCount(), page.limit(),
                page.offset(), page.nextOffset(), page.hasMore(), issueIds(page.issues()));
        if (result instanceof ChangeCheckResult change) return new McpChangeCheckResult("2", change.scanId(), change.available(), change.passed(),
                change.baseline(), change.availability(), change.message(), change.introducedIssueCount(), change.preExistingIssueCount(),
                change.resolvedIssueCount(), change.hasMore(), issueIds(change.issues()));
        if (result instanceof RepoBuddyIssue issue) return issueSummary(issue);
        if (result instanceof RepoBuddyIssueContext context) return compactContext(context);
        return result;
    }

    private static McpIssueSummary issueSummary(RepoBuddyIssue issue) {
        return new McpIssueSummary("3", text(issue.id(), 64), text(issue.ruleId(), 64), text(issue.category(), 64),
                issue.severity(), text(issue.path(), 160), location(issue.line(), issue.column()), issue.line(), issue.column(), text(issue.message(), SUMMARY_TEXT_BYTES),
                text(issue.affectedSymbol(), SYMBOL_TEXT_BYTES), issue.changeStatus(), attribution(issue.attribution()));
    }

    static McpIssueDetails issueDetails(RepoBuddyIssue issue) {
        TextValue explanation = bounded(issue.explanation(), DETAIL_TEXT_BYTES);
        TextValue remediation = bounded(issue.suggestedFix(), DETAIL_TEXT_BYTES);
        return new McpIssueDetails("3", text(issue.id(), 64), explanation.value(), remediation.value(),
                explanation.truncated() || remediation.truncated());
    }

    private static McpIssueContext compactContext(RepoBuddyIssueContext context) {
        TextValue code = bounded(context.context().code(), CONTEXT_TEXT_BYTES);
        return new McpIssueContext("3", text(context.issue().id(), 64), text(context.issue().path(), 160),
                context.context().startLine(), context.context().endLine(), code.value(), context.context().truncated() || code.truncated());
    }

    static McpRulePage rulePage(List<RepoBuddyRule> all, int limit, int offset) {
        int from = Math.min(offset, all.size());
        int to = Math.min(from + limit, all.size());
        return new McpRulePage("3", all.size(), limit, offset, to < all.size() ? to : null, to < all.size(),
                all.subList(from, to).stream().map(RepoBuddyMcpServer::ruleSummary).toList());
    }

    private static McpRuleSummary ruleSummary(RepoBuddyRule rule) {
        return new McpRuleSummary(text(rule.id(), 48), text(rule.name(), 64), text(rule.category(), 48), rule.defaultSeverity(), rule.enabled());
    }

    static McpRuleDetails ruleDetails(RepoBuddyRule rule) {
        TextValue description = bounded(rule.description(), RULE_DETAIL_TEXT_BYTES);
        TextValue recommendation = bounded(rule.recommendation(), RULE_DETAIL_TEXT_BYTES);
        List<String> falsePositives = rule.commonFalsePositives().stream().limit(2).map(value -> text(value, 96)).toList();
        boolean truncated = description.truncated() || recommendation.truncated() || rule.commonFalsePositives().size() > falsePositives.size()
                || rule.commonFalsePositives().stream().anyMatch(value -> utf8Length(value) > 96);
        return new McpRuleDetails("3", text(rule.id(), 48), description.value(), recommendation.value(), falsePositives, truncated);
    }

    private static McpChangeAttribution attribution(ChangeAttribution value) {
        if (value == null) return null;
        return new McpChangeAttribution(value.status(), text(value.changedFile(), 120), value.directlyTouched(), value.reason());
    }

    private static TextValue bounded(String value, int byteLimit) {
        if (value == null) return new TextValue(null, false);
        if (utf8Length(value) <= byteLimit) return new TextValue(value, false);
        String suffix = "…";
        int available = byteLimit - utf8Length(suffix);
        StringBuilder result = new StringBuilder();
        int used = 0;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            String character = new String(Character.toChars(codePoint));
            int bytes = utf8Length(character);
            if (used + bytes > available) break;
            result.append(character); used += bytes; offset += Character.charCount(codePoint);
        }
        return new TextValue(result.append(suffix).toString(), true);
    }

    private static String text(String value, int byteLimit) { return bounded(value, byteLimit).value(); }
    private static int utf8Length(String value) { return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length; }
    private static String location(Integer line, Integer column) {
        if (line == null) return null;
        return column == null ? Integer.toString(line) : line + ":" + column;
    }

    private static List<String> issueIds(List<RepoBuddyIssue> issues) {
        return issues.stream().map(RepoBuddyIssue::id).toList();
    }

    static String serializedResponse(JsonElement id, JsonObject response) {
        String serialized = RepoBuddyJson.gson(false).toJson(response);
        if (serialized.getBytes(StandardCharsets.UTF_8).length <= MAX_RESPONSE_BYTES) return serialized;
        return RepoBuddyJson.gson(false).toJson(success(id, toolError(
                "REPOBUDDY_OUTPUT_LIMIT_EXCEEDED: MCP response exceeds 1 KB; request less data or use a more specific tool")));
    }

    private record McpIssuePage(String apiVersion, String scanId, int total, int limit, int offset,
                                Integer nextOffset, boolean hasMore, List<String> issueIds) {}

    private record McpScopedIssuePage(String apiVersion, String scanId, AnalysisScope scope, String baseline,
                                      ChangeAvailability availability, String message, int total, int preExistingIssueCount,
                                      int resolvedIssueCount, int limit, int offset, Integer nextOffset, boolean hasMore,
                                      List<String> issueIds) {}

    private record McpChangeCheckResult(String apiVersion, String scanId, boolean available, boolean passed,
                                        String baseline, ChangeAvailability availability, String message, int introducedIssueCount,
                                        int preExistingIssueCount, int resolvedIssueCount, boolean hasMore, List<String> issueIds) {}
    private record McpIssueSummary(String apiVersion, String id, String ruleId, String category, Severity severity,
                                   String path, String location, Integer line, Integer column, String message, String affectedSymbol,
                                   IssueChangeStatus changeStatus, McpChangeAttribution attribution) {}
    private record McpChangeAttribution(IssueChangeStatus status, String changedFile, boolean directlyTouched,
                                        ChangeAttributionReason reason) {}
    record McpIssueDetails(String apiVersion, String issueId, String explanation, String remediation,
                                   boolean truncated) {}
    private record McpIssueContext(String apiVersion, String issueId, String path, int startLine, int endLine,
                                   String code, boolean truncated) {}
    record McpRulePage(String apiVersion, int total, int limit, int offset, Integer nextOffset,
                               boolean hasMore, List<McpRuleSummary> rules) {}
    private record McpRuleSummary(String id, String name, String category, Severity defaultSeverity, boolean enabled) {}
    record McpRuleDetails(String apiVersion, String ruleId, String description, String recommendation,
                                  List<String> falsePositiveGuidance, boolean truncated) {}
    private record TextValue(String value, boolean truncated) {}

    static String summary(Object result) {
        if (result == null) return "No result";
        if (result instanceof RepoBuddyScanResult scan) {
            return "Scan " + scan.scanId() + ": " + scan.summary().totalIssues() + " issue"
                    + (scan.summary().totalIssues() == 1 ? "" : "s")
                    + " in " + scan.durationMs() + " ms";
        }
        if (result instanceof IssuePage page) {
            return page.issues().size() + " issue" + (page.issues().size() == 1 ? "" : "s")
                    + " on this page of " + page.total() + " total"
                    + (page.hasMore() ? "; more available" : "");
        }
        if (result instanceof ScopedIssuePage page) {
            return page.issues().size() + " issue" + (page.issues().size() == 1 ? "" : "s")
                    + " on this " + page.scope().name().toLowerCase()
                    + " page of " + page.total() + " total"
                    + (page.hasMore() ? "; more available" : "");
        }
        if (result instanceof ChangeCheckResult change) {
            return (change.passed() ? "No introduced issues" : change.introducedIssueCount() + " introduced issue"
                    + (change.introducedIssueCount() == 1 ? "" : "s"))
                    + "; " + change.preExistingIssueCount() + " pre-existing, "
                    + change.resolvedIssueCount() + " resolved"
                    + (change.hasMore() ? "; more available" : "");
        }
        if (result instanceof RepoBuddyProjectInfo info) {
            StringBuilder value = new StringBuilder(info.name()).append(" (").append(info.buildSystem());
            if (info.javaVersion() != null && !info.javaVersion().isBlank()) value.append(", Java ").append(info.javaVersion());
            if (info.springBoot()) value.append(", Spring Boot");
            if (info.springDataJpa()) value.append(", Spring Data JPA");
            value.append(")");
            return value.toString();
        }
        if (result instanceof RepoBuddyIssue issue) {
            return issue.ruleId() + " at " + issue.path() + (issue.line() == null ? "" : ":" + issue.line());
        }
        if (result instanceof McpIssueSummary issue) return issue.ruleId() + " at " + issue.path()
                + (issue.line() == null ? "" : ":" + issue.line());
        if (result instanceof McpIssueDetails issue) return "Details for " + issue.issueId()
                + (issue.truncated() ? " (truncated)" : "");
        if (result instanceof RepoBuddyIssueContext context) {
            return "Context for " + context.issue().id() + ": lines " + context.context().startLine()
                    + "-" + context.context().endLine() + (context.context().truncated() ? " (truncated)" : "");
        }
        if (result instanceof McpIssueContext context) return "Context for " + context.issueId() + ": lines "
                + context.startLine() + "-" + context.endLine() + (context.truncated() ? " (truncated)" : "");
        if (result instanceof McpRulePage page) return page.rules().size() + " rule"
                + (page.rules().size() == 1 ? "" : "s") + " on this page of " + page.total() + " total"
                + (page.hasMore() ? "; more available" : "");
        if (result instanceof McpRuleDetails rule) return "Details for " + rule.ruleId()
                + (rule.truncated() ? " (truncated)" : "");
        if (result instanceof List<?> list) {
            if (list.isEmpty()) return "No items";
            Object first = list.get(0);
            if (first instanceof RepoBuddyRule) return list.size() + " rule" + (list.size() == 1 ? "" : "s");
            if (first instanceof RepoBuddyProjectRef) return list.size() + " project" + (list.size() == 1 ? "" : "s");
            if (first instanceof RepoBuddyIssue) return list.size() + " issue" + (list.size() == 1 ? "" : "s");
            return list.size() + " item" + (list.size() == 1 ? "" : "s");
        }
        return RepoBuddyJson.gson(false).toJson(result);
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
