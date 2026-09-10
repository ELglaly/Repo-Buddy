package com.repoinspector.core;
import java.util.List;
public record IssuePage(String apiVersion, String scanId, int total, int limit, int offset,
        Integer nextOffset, boolean hasMore, List<RepoBuddyIssue> issues) {
    public IssuePage(String apiVersion, String scanId, int total, int limit, int offset,
            List<RepoBuddyIssue> issues) {
        this(apiVersion, scanId, total, limit, offset,
                offset + issues.size() < total ? offset + issues.size() : null,
                offset + issues.size() < total, issues);
    }
    public IssuePage { issues = List.copyOf(issues); }
}
