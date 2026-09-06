package com.repoinspector.core;
public record ScanRequest(Severity severity, String ruleId) {
    public ScanRequest {
        if (ruleId != null && RepoBuddyRules.byId(ruleId) == null)
            throw new IllegalArgumentException("Unknown RepoBuddy rule: " + ruleId);
    }
    public static ScanRequest all() { return new ScanRequest(null, null); }
}
