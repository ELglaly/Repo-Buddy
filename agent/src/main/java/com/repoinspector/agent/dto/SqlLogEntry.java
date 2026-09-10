package com.repoinspector.agent.dto;

/**
 * A single SQL statement captured during a repository method execution.
 *
 * @param sql         the raw SQL string intercepted by Hibernate
 * @param capturedAt  epoch-millis timestamp when the statement was captured
 * @param truncated   whether the SQL text exceeded the capture limit
 */
public record SqlLogEntry(String sql, long capturedAt, boolean truncated) {
    /** Source-compatible constructor retained for existing agent clients. */
    public SqlLogEntry(String sql, long capturedAt) {
        this(sql, capturedAt, false);
    }
}
