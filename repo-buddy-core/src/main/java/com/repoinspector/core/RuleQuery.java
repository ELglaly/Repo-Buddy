package com.repoinspector.core;
public record RuleQuery(Boolean enabled, String ruleId) {
    public static RuleQuery all() { return new RuleQuery(null, null); }
}
