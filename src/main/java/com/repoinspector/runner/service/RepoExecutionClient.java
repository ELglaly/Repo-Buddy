package com.repoinspector.runner.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.repoinspector.runner.model.ExecutionRequest;
import com.repoinspector.runner.model.ExecutionResult;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * HTTP client that calls the RepoBuddy agent's
 * {@code POST /repoinspector/execute} endpoint running inside the Spring Boot app.
 *
 * <p>The agent invokes the real Spring Data repository bean so that JPA/Hibernate
 * generates and executes the actual query — no query simulation is done here.
 *
 * <p>Must not be called on the Event Dispatch Thread.
 */
public final class RepoExecutionClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final Gson GSON = new GsonBuilder().create();

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final String baseUrl;

    /**
     * @param baseUrl the Spring Boot app base URL, e.g. {@code "http://localhost:8080"}
     */
    public RepoExecutionClient(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;
    }

    /**
     * Sends the execution request to the running application and returns the result.
     *
     * @param request the repository method invocation descriptor
     * @return parsed {@link ExecutionResult} from the agent
     * @throws Exception on network failure, timeout, or non-200 response
     */
    public ExecutionResult execute(ExecutionRequest request) throws Exception {
        String body = GSON.toJson(request);

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/repoinspector/execute"))
                .header("Content-Type", "application/json")
                .timeout(TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = httpClient.send(
                httpRequest, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Agent returned HTTP " + response.statusCode()
                    + ": " + response.body());
        }

        return GSON.fromJson(response.body(), ExecutionResult.class);
    }
}
