package com.repoinspector.runner.model;

import java.util.List;

/**
 * JSON response from the agent's {@code POST /repoinspector/execute} endpoint.
 */
public record ExecutionResult(
        String status,
        String result,
        List<SqlLogEntry> sqlLogs,
        long executionTimeMs,
        String exception
) {
    public boolean isSuccess() {
        return "SUCCESS".equals(status);
    }
}
