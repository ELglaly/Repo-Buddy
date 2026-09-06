package com.repoinspector.ipc;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.repoinspector.core.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class RepoBuddyIpcClient implements RepoBuddyApplicationService {
    private final SessionDescriptor session;
    private final HttpClient client;
    private final Duration timeout;

    public RepoBuddyIpcClient(SessionDescriptor session, Duration timeout) {
        this.session = session;
        this.timeout = timeout;
        this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    public SessionDescriptor session() { return session; }

    @Override public CompletionStage<RepoBuddyScanResult> scan(ScanRequest request) { return call("scan", request, RepoBuddyScanResult.class); }
    @Override public CompletionStage<IssuePage> listIssues(IssueQuery query) { return call("issues", query, IssuePage.class); }
    @Override public CompletionStage<RepoBuddyIssue> getIssue(String id) { return call("issue", payload("issueId", id), RepoBuddyIssue.class); }
    @Override public CompletionStage<RepoBuddyIssueContext> getIssueContext(String id, int lines) {
        JsonObject object = payload("issueId", id); object.addProperty("lines", lines);
        return call("context", object, RepoBuddyIssueContext.class);
    }
    @Override public CompletionStage<List<RepoBuddyRule>> getRules(RuleQuery query) {
        return call("rules", query, RepoBuddyRule[].class).thenApply(value -> Arrays.asList(value));
    }
    @Override public CompletionStage<RepoBuddyProjectInfo> getProjectInfo() { return call("project-info", new JsonObject(), RepoBuddyProjectInfo.class); }
    @Override public CompletionStage<ScopedIssuePage> getIssues(ScopedIssueRequest request) {
        return call("scoped-issues", request, ScopedIssuePage.class);
    }
    @Override public CompletionStage<ChangeCheckResult> checkChanges(Severity severity, String ruleId) {
        JsonObject value = new JsonObject();
        if (severity != null) value.addProperty("severity", severity.name());
        if (ruleId != null) value.addProperty("ruleId", ruleId);
        return call("check-changes", value, ChangeCheckResult.class);
    }

    private <T> CompletionStage<T> call(String action, Object payload, Class<T> resultType) {
        JsonElement tree = payload instanceof JsonElement element ? element : RepoBuddyJson.gson(false).toJsonTree(payload);
        IpcRequest envelope = new IpcRequest(IpcRequest.VERSION, action, session.projectId(), tree.getAsJsonObject());
        String body = RepoBuddyJson.gson(false).toJson(envelope);
        if (body.length() > 65_536) return CompletableFuture.failedFuture(new RepoBuddyException(
                RepoBuddyErrorCode.REPOBUDDY_OUTPUT_LIMIT_EXCEEDED, "IPC request exceeds 64 KB"));
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + session.port() + "/v1/command"))
                .timeout(timeout).header("Authorization", "Bearer " + session.token())
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).handle((response, connectionError) -> {
            if (connectionError != null) throw new RepoBuddyException(RepoBuddyErrorCode.REPOBUDDY_IDE_NOT_RUNNING,
                    "Cannot connect to the RepoBuddy IntelliJ integration");
            IpcResponse parsed = RepoBuddyJson.gson(false).fromJson(response.body(), IpcResponse.class);
            if (parsed == null) throw new RepoBuddyException(RepoBuddyErrorCode.REPOBUDDY_INTERNAL_ERROR, "Empty IPC response");
            if (parsed.error() != null) throw new RepoBuddyException(parsed.error().error().code(), parsed.error().error().message());
            return RepoBuddyJson.gson(false).fromJson(parsed.result(), resultType);
        });
    }

    private static JsonObject payload(String key, String value) { JsonObject object = new JsonObject(); object.addProperty(key, value); return object; }
}
