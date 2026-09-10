package com.repoinspector.agent.dto;

import java.util.List;

/**
 * The result of a repository method execution sent back to the IDE plugin.
 *
 * @param status         "SUCCESS" or "FAILURE"
 * @param result         JSON-serialized return value; null on failure
 * @param sqlLogs        SQL statements captured during execution (empty when Hibernate not present)
 * @param executionTimeMs wall-clock duration of the method invocation in milliseconds
 * @param exception      full stack-trace string; null on success
 * @param droppedSqlCount statements omitted after the bounded SQL buffer filled
 * @param sqlOverflow     whether at least one SQL statement was omitted
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
    /** Source-compatible constructor retained for existing callers. */
    public ExecutionResult(String status, String result, List<SqlLogEntry> sqlLogs,
                           long executionTimeMs, String exception) {
        this(status, result, sqlLogs, executionTimeMs, exception, 0, false);
    }
}
