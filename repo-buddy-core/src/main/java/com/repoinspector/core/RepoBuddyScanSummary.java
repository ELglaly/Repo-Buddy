package com.repoinspector.core;
import java.util.Map;
public record RepoBuddyScanSummary(int totalIssues, Map<Severity, Integer> severityCounts,
        Map<String, Integer> ruleCounts) {
    public RepoBuddyScanSummary { severityCounts = Map.copyOf(severityCounts); ruleCounts = Map.copyOf(ruleCounts); }
}
