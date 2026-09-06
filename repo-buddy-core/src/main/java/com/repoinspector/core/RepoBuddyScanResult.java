package com.repoinspector.core;
import java.time.Instant;
public record RepoBuddyScanResult(String apiVersion, String scanId, ScanStatus status, long durationMs,
        Instant completedAt, ProjectRef project, RepoBuddyScanSummary summary) {
    public record ProjectRef(String id, String name, String root) {}
}
