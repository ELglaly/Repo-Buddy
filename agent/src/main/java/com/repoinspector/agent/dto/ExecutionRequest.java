package com.repoinspector.agent.dto;

import java.util.List;

/**
 * Describes a repository method invocation requested by the IDE plugin.
 *
 * @param repositoryClass fully-qualified interface name, e.g. "com.example.UserRepository"
 * @param methodName      simple method name, e.g. "findById"
 * @param parameters      ordered list of parameter type + value pairs
 */
public record ExecutionRequest(
        String repositoryClass,
        String methodName,
        List<ParameterValue> parameters
) {
    /** A single parameter: the declared type name and the raw string value from the plugin UI. */
    public record ParameterValue(String type, String value) {}
}
