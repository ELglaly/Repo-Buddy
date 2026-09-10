package com.repoinspector.integration;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.util.Disposer;
import com.repoinspector.core.*;
import com.repoinspector.inspections.scan.IntelliJRepoBuddyApplicationService;
import com.repoinspector.ipc.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Authenticated loopback-only adapter for local CLI and MCP clients. */
@Service
public final class RepoBuddyLocalApiServer implements Disposable {
    private static final Logger LOG = Logger.getInstance(RepoBuddyLocalApiServer.class);
    private static final int MAX_REQUEST_BYTES = 64 * 1024;
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private final Map<String, Binding> byToken = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private HttpServer server;
    private ExecutorService executor;

    private record Binding(Project project, String projectId, Path descriptor) {}

    public static RepoBuddyLocalApiServer getInstance() {
        return ApplicationManager.getApplication().getService(RepoBuddyLocalApiServer.class);
    }

    public synchronized void enable() {
        if (server != null) return;
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
            executor = Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "RepoBuddy local API");
                thread.setDaemon(true);
                return thread;
            });
            server.setExecutor(executor);
            server.createContext("/v1/command", this::handle);
            server.start();
            for (Project project : ProjectManager.getInstance().getOpenProjects()) register(project);
        } catch (IOException error) {
            LOG.warn("Unable to start RepoBuddy local integration", error);
            disable();
        }
    }

    public synchronized void register(Project project) {
        if (server == null || project.isDisposed() || project.getBasePath() == null) return;
        IntelliJRepoBuddyApplicationService application = IntelliJRepoBuddyApplicationService.getInstance(project);
        if (byToken.values().stream().anyMatch(value -> value.project() == project)) return;
        byte[] tokenBytes = new byte[32];
        random.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        try {
            SessionDescriptor descriptor = new SessionDescriptor(IpcRequest.VERSION, server.getAddress().getPort(),
                    ProcessHandle.current().pid(), application.projectId(), project.getName(),
                    Path.of(project.getBasePath()).toRealPath().toString(), token);
            Path file = SessionDiscovery.write(descriptor);
            byToken.put(token, new Binding(project, descriptor.projectId(), file));
            Disposer.register(project, () -> unregister(project));
        } catch (IOException error) {
            LOG.warn("Unable to create a secure RepoBuddy integration descriptor", error);
        }
    }

    public synchronized void unregister(Project project) {
        byToken.entrySet().removeIf(entry -> {
            if (entry.getValue().project() != project) return false;
            try { Files.deleteIfExists(entry.getValue().descriptor()); } catch (IOException ignored) { }
            return true;
        });
    }

    public synchronized void disable() {
        byToken.values().forEach(binding -> {
            try { Files.deleteIfExists(binding.descriptor()); } catch (IOException ignored) { }
        });
        byToken.clear();
        if (server != null) server.stop(0);
        server = null;
        if (executor != null) executor.shutdownNow();
        executor = null;
    }

    private void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) { send(exchange, 405, failure(RepoBuddyErrorCode.REPOBUDDY_INVALID_ARGUMENT, "POST required")); return; }
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        String token = authorization != null && authorization.startsWith("Bearer ") ? authorization.substring(7) : "";
        Binding binding = byToken.get(token);
        if (binding == null) { send(exchange, 401, failure(RepoBuddyErrorCode.REPOBUDDY_AUTHENTICATION_FAILED, "Authentication failed")); return; }
        byte[] requestBytes = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
        if (requestBytes.length > MAX_REQUEST_BYTES) { send(exchange, 413, failure(RepoBuddyErrorCode.REPOBUDDY_OUTPUT_LIMIT_EXCEEDED, "Request exceeds 64 KB")); return; }
        try {
            IpcRequest request = RepoBuddyJson.gson(false).fromJson(new String(requestBytes, StandardCharsets.UTF_8), IpcRequest.class);
            if (request == null || !IpcRequest.VERSION.equals(request.protocolVersion())) throw new RepoBuddyException(
                    RepoBuddyErrorCode.REPOBUDDY_PROTOCOL_MISMATCH, "Unsupported RepoBuddy protocol version");
            if (!binding.projectId().equals(request.projectId()) || binding.project().isDisposed()) throw new RepoBuddyException(
                    RepoBuddyErrorCode.REPOBUDDY_PROJECT_DISPOSED, "Selected project is unavailable");
            Object result = dispatch(IntelliJRepoBuddyApplicationService.getInstance(binding.project()), request);
            send(exchange, 200, IpcResponse.success(RepoBuddyJson.gson(false).toJsonTree(result)));
        } catch (JsonParseException | IllegalArgumentException error) {
            send(exchange, 400, failure(RepoBuddyErrorCode.REPOBUDDY_INVALID_ARGUMENT, "Malformed request"));
        } catch (RuntimeException error) {
            Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
            RepoBuddyException repoError = cause instanceof RepoBuddyException value ? value
                    : new RepoBuddyException(RepoBuddyErrorCode.REPOBUDDY_INTERNAL_ERROR, "RepoBuddy request failed");
            send(exchange, 400, IpcResponse.failure(RepoBuddyError.of(repoError)));
        }
    }

    private Object dispatch(RepoBuddyApplicationService service, IpcRequest request) {
        JsonObject payload = request.payload() == null ? new JsonObject() : request.payload();
        try {
            return switch (request.action()) {
                case "scan" -> service.scan(RepoBuddyJson.gson(false).fromJson(payload, ScanRequest.class)).toCompletableFuture().get(60, TimeUnit.SECONDS);
                case "issues" -> service.listIssues(RepoBuddyJson.gson(false).fromJson(payload, IssueQuery.class)).toCompletableFuture().get(10, TimeUnit.SECONDS);
                case "issue" -> service.getIssue(required(payload, "issueId")).toCompletableFuture().get(10, TimeUnit.SECONDS);
                case "context" -> service.getIssueContext(required(payload, "issueId"), payload.has("lines") ? payload.get("lines").getAsInt() : 10).toCompletableFuture().get(10, TimeUnit.SECONDS);
                case "rules" -> service.getRules(RepoBuddyJson.gson(false).fromJson(payload, RuleQuery.class)).toCompletableFuture().get(10, TimeUnit.SECONDS);
                case "project-info" -> service.getProjectInfo().toCompletableFuture().get(10, TimeUnit.SECONDS);
                case "scoped-issues" -> service.getIssues(RepoBuddyJson.gson(false).fromJson(payload, ScopedIssueRequest.class)).toCompletableFuture().get(60, TimeUnit.SECONDS);
                case "check-changes" -> service.checkChanges(payload.has("severity") ? Severity.valueOf(payload.get("severity").getAsString()) : null,
                        payload.has("ruleId") ? payload.get("ruleId").getAsString() : null).toCompletableFuture().get(60, TimeUnit.SECONDS);
                default -> throw new RepoBuddyException(RepoBuddyErrorCode.REPOBUDDY_INVALID_ARGUMENT, "Unknown action: " + request.action());
            };
        } catch (RepoBuddyException error) { throw error; }
        catch (java.util.concurrent.TimeoutException error) { throw new RepoBuddyException(RepoBuddyErrorCode.REPOBUDDY_SCAN_TIMEOUT, "RepoBuddy request timed out"); }
        catch (Exception error) { throw new CompletionException(error.getCause() == null ? error : error.getCause()); }
    }

    private static String required(JsonObject payload, String name) {
        if (!payload.has(name) || payload.get(name).getAsString().isBlank()) throw new RepoBuddyException(
                RepoBuddyErrorCode.REPOBUDDY_INVALID_ARGUMENT, name + " is required");
        return payload.get(name).getAsString();
    }

    private static IpcResponse failure(RepoBuddyErrorCode code, String message) {
        return IpcResponse.failure(RepoBuddyError.of(new RepoBuddyException(code, message)));
    }

    private static void send(HttpExchange exchange, int status, IpcResponse response) throws IOException {
        byte[] body = RepoBuddyJson.gson(false).toJson(response).getBytes(StandardCharsets.UTF_8);
        if (body.length > MAX_RESPONSE_BYTES) {
            response = failure(RepoBuddyErrorCode.REPOBUDDY_OUTPUT_LIMIT_EXCEEDED, "Response exceeds 1 MB");
            body = RepoBuddyJson.gson(false).toJson(response).getBytes(StandardCharsets.UTF_8);
            status = 413;
        }
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    @Override public void dispose() { disable(); }
}
