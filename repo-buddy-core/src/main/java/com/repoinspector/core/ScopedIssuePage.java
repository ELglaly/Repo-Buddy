package com.repoinspector.core;
import java.util.List;
public record ScopedIssuePage(String apiVersion, String scanId, AnalysisScope scope, String baseline,
        ChangeAvailability availability, String message, int total, int preExistingIssueCount,
        int resolvedIssueCount, int limit, int offset, Integer nextOffset, boolean hasMore,
        List<RepoBuddyIssue> issues) {
    public ScopedIssuePage { issues = List.copyOf(issues); }
}
