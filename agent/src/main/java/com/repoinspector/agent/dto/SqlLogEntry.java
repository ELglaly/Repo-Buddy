package com.repoinspector.agent.dto;

/**
 * A single SQL statement captured during a repository method execution.
 *
 * @param sql         the raw SQL string intercepted by Hibernate
 * @param capturedAt  epoch-millis timestamp when the statement was captured
 */
public record SqlLogEntry(String sql, long capturedAt) {}
