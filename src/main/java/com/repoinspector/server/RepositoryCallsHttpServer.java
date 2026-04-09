package com.repoinspector.server;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.repoinspector.analysis.CallChainAnalyzer;
import com.repoinspector.analysis.CallChainCache;
import com.repoinspector.analysis.CallSiteAnalyzer;
import com.repoinspector.analysis.EndpointFinder;
import com.repoinspector.analysis.OutboundApiCallFinder;
import com.repoinspector.analysis.OutboundCallCache;
import com.repoinspector.model.CallChainNode;
import com.repoinspector.model.EndpointInfo;
import com.repoinspector.model.OperationType;
import com.repoinspector.model.OutboundApiCall;
import com.repoinspector.model.RepositoryMethodInfo;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Embedded HTTP server that exposes repository call-count data via a REST endpoint.
 *
 * <p>Starts automatically when the IDE launches (registered as an application service).
 * Listens on {@value #PORT} by default.
 *
 * <pre>
 * GET http://localhost:7891/api/repository-calls
 * → JSON array of { repositoryName, methodName, methodSignature, callCount }
 *
 * GET http://localhost:7891/api/endpoint-calls?path=/login
 * → JSON with endpoint info + repository nodes reachable from that endpoint
 *
 * GET http://localhost:7891/api/endpoints
 * → JSON array of all discovered Spring endpoints
 *
 * GET http://localhost:7891/health
 * → { "status": "ok" }
 * </pre>
 */
public class RepositoryCallsHttpServer implements Disposable {

    private static final Logger LOG = Logger.getInstance(RepositoryCallsHttpServer.class);
    public static final int PORT = 7891;

    private HttpServer server;

    /** Called by the IntelliJ platform when the application starts. */
    public RepositoryCallsHttpServer() {
        startServer();
    }

    private void startServer() {
        try {
            server = HttpServer.create(new InetSocketAddress(PORT), 0);
            server.createContext("/api/repository-calls", this::handleRepositoryCalls);
            server.createContext("/api/endpoint-calls", this::handleEndpointCalls);
            server.createContext("/api/endpoints", this::handleEndpoints);
            server.createContext("/api/outbound-calls", this::handleOutboundCalls);
            server.createContext("/health", this::handleHealth);
            server.setExecutor(Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "RepoBuddy-HTTP");
                t.setDaemon(true);
                return t;
            }));
            server.start();
            LOG.info("RepoBuddy HTTP server started on port " + PORT);
        } catch (IOException e) {
            LOG.warn("RepoBuddy HTTP server could not start on port " + PORT + ": " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Handlers
    // -------------------------------------------------------------------------

    private void handleRepositoryCalls(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
            return;
        }

        Project project = getOpenProject();
        if (project == null) {
            sendResponse(exchange, 503, "{\"error\":\"No project is currently open in the IDE\"}");
            return;
        }

        CompletableFuture<CallSiteAnalyzer.AnalysisResult> future = new CompletableFuture<>();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                future.complete(CallSiteAnalyzer.analyzeAll(project));
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        CallSiteAnalyzer.AnalysisResult result;
        try {
            result = future.get(30, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            sendResponse(exchange, 500, "{\"error\":\"Analysis timed out\"}");
            return;
        } catch (Exception e) {
            sendResponse(exchange, 500, "{\"error\":\"Analysis failed: " + jsonString(e.getMessage()) + "}");
            return;
        }

        String json = toJson(result.getInfos());
        sendResponse(exchange, 200, json);
    }

    /**
     * GET /api/endpoints
     * Returns a JSON array of all discovered Spring REST endpoints in the open project.
     * Use this to discover valid path values for /api/endpoint-calls.
     */
    private void handleEndpoints(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
            return;
        }

        Project project = getOpenProject();
        if (project == null) {
            sendResponse(exchange, 503, "{\"error\":\"No project is currently open in the IDE\"}");
            return;
        }

        CompletableFuture<List<EndpointInfo>> future = new CompletableFuture<>();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                future.complete(EndpointFinder.findAllEndpoints(project));
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        List<EndpointInfo> endpoints;
        try {
            endpoints = future.get(30, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            sendResponse(exchange, 500, "{\"error\":\"Analysis timed out\"}");
            return;
        } catch (Exception e) {
            sendResponse(exchange, 500, "{\"error\":\"Analysis failed: " + jsonString(e.getMessage()) + "}");
            return;
        }

        StringBuilder sb = new StringBuilder("[\n");
        for (int i = 0; i < endpoints.size(); i++) {
            EndpointInfo ep = endpoints.get(i);
            sb.append("  {")
                    .append("\"httpMethod\":").append(jsonString(ep.httpMethod())).append(',')
                    .append("\"path\":").append(jsonString(ep.path())).append(',')
                    .append("\"controller\":").append(jsonString(ep.controllerName())).append(',')
                    .append("\"method\":").append(jsonString(ep.methodSignature()))
                    .append('}');
            if (i < endpoints.size() - 1) sb.append(',');
            sb.append('\n');
        }
        sb.append(']');
        sendResponse(exchange, 200, sb.toString());
    }

    /**
     * GET /api/endpoint-calls?path=/login
     * GET /api/endpoint-calls?path=/login&method=POST   (optional HTTP method filter)
     *
     * Traces the call chain from the matching Spring endpoint and returns every
     * repository method reachable from it, along with READ/WRITE classification,
     * entity type, depth, and @Transactional status.
     */
    private void handleEndpointCalls(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
            return;
        }

        Project project = getOpenProject();
        if (project == null) {
            sendResponse(exchange, 503, "{\"error\":\"No project is currently open in the IDE\"}");
            return;
        }

        // Parse query params
        String query = exchange.getRequestURI().getQuery();
        String pathFilter = queryParam(query, "path");
        String methodFilter = queryParam(query, "method"); // optional

        if (pathFilter == null || pathFilter.isBlank()) {
            sendResponse(exchange, 400,
                    "{\"error\":\"Missing required query param: path (e.g. ?path=/login)\"}");
            return;
        }

        // Find + analyze on a background thread
        record EndpointResult(EndpointInfo ep, List<CallChainNode> nodes) {}

        CompletableFuture<EndpointResult> future = new CompletableFuture<>();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                List<EndpointInfo> endpoints = EndpointFinder.findAllEndpoints(project);
                EndpointInfo match = endpoints.stream()
                        .filter(ep -> pathMatches(ep.path(), pathFilter))
                        .filter(ep -> methodFilter == null || ep.httpMethod().equalsIgnoreCase(methodFilter))
                        .findFirst()
                        .orElse(null);
                if (match == null) {
                    future.complete(null);
                } else {
                    List<CallChainNode> cached = CallChainCache.getOrNull(match, project);
                    if (cached == null) {
                        cached = CallChainAnalyzer.analyze(match, project);
                        CallChainCache.put(match, project, cached);
                    }
                    future.complete(new EndpointResult(match, cached));
                }
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        EndpointResult result;
        try {
            result = future.get(30, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            sendResponse(exchange, 500, "{\"error\":\"Analysis timed out\"}");
            return;
        } catch (Exception e) {
            sendResponse(exchange, 500, "{\"error\":\"Analysis failed: " + jsonString(e.getMessage()) + "}");
            return;
        }

        if (result == null) {
            sendResponse(exchange, 404,
                    "{\"error\":\"No endpoint found matching path=" + jsonString(pathFilter) +
                    (methodFilter != null ? " method=" + methodFilter : "") + "\"}");
            return;
        }

        EndpointInfo ep = result.ep();
        List<CallChainNode> nodes = result.nodes();

        sendResponse(exchange, 200, endpointCallsToJson(ep, nodes));
    }

    /**
     * GET /api/outbound-calls
     * GET /api/outbound-calls?endpoint=/api/login   (optional filter by inbound endpoint path)
     *
     * Returns a JSON array of every outbound HTTP API call detected in the open project,
     * with resolved URLs, HTTP methods, headers, body fields, and confidence levels.
     * When {@code ?endpoint=} is supplied, restricts results to calls reachable from that
     * inbound Spring endpoint via the existing DFS call-chain analysis.
     */
    private void handleOutboundCalls(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
            return;
        }

        Project project = getOpenProject();
        if (project == null) {
            sendResponse(exchange, 503, "{\"error\":\"No project is currently open in the IDE\"}");
            return;
        }

        if (DumbService.getInstance(project).isDumb()) {
            sendResponse(exchange, 503, "{\"error\":\"IDE index is still being built — try again shortly\"}");
            return;
        }

        String query = exchange.getRequestURI().getQuery();
        String endpointFilter = queryParam(query, "endpoint"); // optional

        CompletableFuture<List<OutboundApiCall>> future = new CompletableFuture<>();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                if (endpointFilter != null && !endpointFilter.isBlank()) {
                    List<EndpointInfo> endpoints = EndpointFinder.findAllEndpoints(project);
                    EndpointInfo match = endpoints.stream()
                            .filter(ep -> pathMatches(ep.path(), endpointFilter))
                            .findFirst()
                            .orElse(null);
                    future.complete(match != null
                            ? OutboundApiCallFinder.findReachableFrom(match, project)
                            : List.of());
                } else {
                    OutboundCallCache.clear(); // force fresh scan in case stale empty result is cached
                    List<OutboundApiCall> calls = OutboundApiCallFinder.findAll(project);
                    OutboundCallCache.put(project, calls);
                    future.complete(calls);
                }
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        List<OutboundApiCall> calls;
        try {
            calls = future.get(30, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            sendResponse(exchange, 500, "{\"error\":\"Analysis timed out\"}");
            return;
        } catch (Exception e) {
            sendResponse(exchange, 500, "{\"error\":\"Analysis failed: " + jsonString(e.getMessage()) + "}");
            return;
        }

        sendResponse(exchange, 200, outboundCallsToJson(calls));
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        sendResponse(exchange, 200, "{\"status\":\"ok\",\"port\":" + PORT + "}");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String outboundCallsToJson(List<OutboundApiCall> calls) {
        StringBuilder sb = new StringBuilder("[\n");
        for (int i = 0; i < calls.size(); i++) {
            OutboundApiCall c = calls.get(i);
            sb.append("  {")
                    .append("\"callerClass\":").append(jsonString(c.callerClass())).append(',')
                    .append("\"callerMethod\":").append(jsonString(c.callerMethod())).append(',')
                    .append("\"httpMethod\":").append(jsonString(c.httpMethod())).append(',')
                    .append("\"resolvedUrl\":").append(jsonString(c.resolvedUrl())).append(',')
                    .append("\"headers\":").append(mapToJson(c.headers())).append(',')
                    .append("\"queryParams\":").append(mapToJson(c.queryParams())).append(',')
                    .append("\"bodyFields\":").append(listToJsonOrNull(c.bodyFields())).append(',')
                    .append("\"confidence\":").append(jsonString(c.confidence().name())).append(',')
                    .append("\"reason\":").append(jsonString(c.reason())).append(',')
                    .append("\"clientType\":").append(jsonString(c.clientType().name()))
                    .append('}');
            if (i < calls.size() - 1) sb.append(',');
            sb.append('\n');
        }
        sb.append(']');
        return sb.toString();
    }

    private static String mapToJson(Map<String, String> map) {
        if (map == null || map.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (!first) sb.append(',');
            sb.append(jsonString(e.getKey())).append(':').append(jsonString(e.getValue()));
            first = false;
        }
        return sb.append('}').toString();
    }

    private static String listToJsonOrNull(List<String> list) {
        if (list == null) return "null";
        if (list.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            sb.append(jsonString(list.get(i)));
            if (i < list.size() - 1) sb.append(',');
        }
        return sb.append(']').toString();
    }

    private static void sendResponse(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    /**
     * Returns the first open, non-default project, or {@code null} if none is open.
     */
    private static Project getOpenProject() {
        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
            if (!project.isDefault() && !project.isDisposed()) {
                return project;
            }
        }
        return null;
    }

    /**
     * Serialises a list of {@link RepositoryMethodInfo} records to a JSON array.
     * Uses manual building to avoid adding a JSON library dependency.
     */
    private static String toJson(List<RepositoryMethodInfo> infos) {
        StringBuilder sb = new StringBuilder("[\n");
        for (int i = 0; i < infos.size(); i++) {
            RepositoryMethodInfo info = infos.get(i);
            sb.append("  {")
                    .append("\"repositoryName\":").append(jsonString(info.repositoryName())).append(',')
                    .append("\"methodName\":").append(jsonString(info.methodName())).append(',')
                    .append("\"methodSignature\":").append(jsonString(info.methodSignature())).append(',')
                    .append("\"callCount\":").append(info.callCount())
                    .append('}');
            if (i < infos.size() - 1) sb.append(',');
            sb.append('\n');
        }
        sb.append(']');
        return sb.toString();
    }

    private static String endpointCallsToJson(EndpointInfo ep, List<CallChainNode> nodes) {
        List<CallChainNode> repoNodes = nodes.stream()
                .filter(CallChainNode::isRepository)
                .toList();

        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"endpoint\": {\n")
                .append("    \"httpMethod\": ").append(jsonString(ep.httpMethod())).append(",\n")
                .append("    \"path\": ").append(jsonString(ep.path())).append(",\n")
                .append("    \"controller\": ").append(jsonString(ep.controllerName())).append(",\n")
                .append("    \"method\": ").append(jsonString(ep.methodSignature())).append("\n")
                .append("  },\n");
        sb.append("  \"callChainDepth\": ").append(nodes.stream().mapToInt(CallChainNode::depth).max().orElse(0)).append(",\n");
        sb.append("  \"repositoryCallCount\": ").append(repoNodes.size()).append(",\n");
        sb.append("  \"repositoryCalls\": [\n");

        for (int i = 0; i < repoNodes.size(); i++) {
            CallChainNode node = repoNodes.get(i);
            sb.append("    {")
                    .append("\"repository\":").append(jsonString(node.className())).append(',')
                    .append("\"method\":").append(jsonString(node.methodSignature())).append(',')
                    .append("\"operation\":").append(jsonString(node.operationType().name())).append(',')
                    .append("\"entity\":").append(jsonString(node.entityName())).append(',')
                    .append("\"depth\":").append(node.depth()).append(',')
                    .append("\"transactional\":").append(node.isTransactional())
                    .append('}');
            if (i < repoNodes.size() - 1) sb.append(',');
            sb.append('\n');
        }

        sb.append("  ],\n");
        sb.append("  \"fullCallChain\": [\n");

        for (int i = 0; i < nodes.size(); i++) {
            CallChainNode node = nodes.get(i);
            if (node.depth() == 0) { // skip endpoint root node
                if (i < nodes.size() - 1) continue;
                else break;
            }
            sb.append("    {")
                    .append("\"class\":").append(jsonString(node.className())).append(',')
                    .append("\"method\":").append(jsonString(node.methodSignature())).append(',')
                    .append("\"depth\":").append(node.depth()).append(',')
                    .append("\"isRepository\":").append(node.isRepository()).append(',')
                    .append("\"operation\":").append(jsonString(node.operationType().name())).append(',')
                    .append("\"entity\":").append(jsonString(node.entityName())).append(',')
                    .append("\"transactional\":").append(node.isTransactional()).append(',')
                    .append("\"dynamic\":").append(node.isDynamic())
                    .append('}');
            if (i < nodes.size() - 1) sb.append(',');
            sb.append('\n');
        }

        sb.append("  ]\n}");
        return sb.toString();
    }

    /** Extracts the value of a named query parameter from a raw query string. */
    private static String queryParam(String query, String name) {
        if (query == null) return null;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            String key = pair.substring(0, eq);
            if (key.equals(name)) {
                return java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    /** True if the endpoint path matches the filter (exact or prefix with wildcard). */
    private static boolean pathMatches(String endpointPath, String filter) {
        if (endpointPath.equals(filter)) return true;
        // treat path variables as wildcards: /users/{id} matches /users/{id} or /users/
        String normalized = endpointPath.replaceAll("\\{[^}]+}", "*");
        return normalized.equals(filter) || endpointPath.startsWith(filter);
    }

    private static String jsonString(String value) {
        if (value == null) return "null";
        return '"' + value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                + '"';
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    public void dispose() {
        if (server != null) {
            server.stop(0);
            LOG.info("RepoBuddy HTTP server stopped.");
        }
    }
}
