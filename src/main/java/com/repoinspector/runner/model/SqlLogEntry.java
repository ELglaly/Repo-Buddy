package com.repoinspector.runner.model;

/** A single SQL statement captured by the agent during method execution. */
public record SqlLogEntry(String sql, long capturedAt) {}
