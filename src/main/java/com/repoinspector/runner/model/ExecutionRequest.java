package com.repoinspector.runner.model;

import java.util.List;

/**
 * JSON payload sent from the IDE plugin to the agent's
 * {@code POST /repoinspector/execute} endpoint.
 */
public record ExecutionRequest(
        String repositoryClass,
        String methodName,
        List<ParameterValue> parameters
) {
    /** One parameter: its declared type name and the raw string value from the UI. */
    public record ParameterValue(String type, String value) {}
}
