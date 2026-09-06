package com.repoinspector.core;
import java.util.List;
public record ChangeCheckResult(String apiVersion, String scanId, boolean available, boolean passed,
        String baseline, ChangeAvailability availability, String message, int introducedIssueCount,
        int preExistingIssueCount, int resolvedIssueCount, boolean hasMore, List<RepoBuddyIssue> issues) {
    public ChangeCheckResult { issues = List.copyOf(issues); }
}
