package com.repoinspector.runner.model;

/** A single SQL statement captured by the agent during method execution. */
public record SqlLogEntry(String sql, long capturedAt, boolean truncated) {
    /** Retained for source compatibility with callers built against the original runtime DTO. */
    public SqlLogEntry(String sql, long capturedAt) {
        this(sql, capturedAt, false);
    }
}
