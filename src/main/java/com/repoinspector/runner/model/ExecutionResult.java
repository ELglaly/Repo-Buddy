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
        String exception,
        int droppedSqlCount,
        boolean sqlOverflow
) {
    /** Retained for source compatibility with callers built against the original runtime DTO. */
    public ExecutionResult(String status, String result, List<SqlLogEntry> sqlLogs,
                           long executionTimeMs, String exception) {
        this(status, result, sqlLogs, executionTimeMs, exception, 0, false);
    }

    public boolean isSuccess() {
        return "SUCCESS".equals(status);
    }
}
