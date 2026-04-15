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
 */
public record ExecutionResult(
        String status,
        String result,
        List<SqlLogEntry> sqlLogs,
        long executionTimeMs,
        String exception
) {}
