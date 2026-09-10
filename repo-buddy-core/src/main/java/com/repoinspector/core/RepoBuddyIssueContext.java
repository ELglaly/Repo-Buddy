package com.repoinspector.core;
public record RepoBuddyIssueContext(String apiVersion, IssueRef issue, Context context) {
    public record IssueRef(String id, String path, Integer line) {}
    public record Context(int startLine, int endLine, String code, boolean truncated) {}
}
