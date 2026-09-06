package com.repoinspector.core;
import java.util.List;
public record RepoBuddyRule(String id, String idPrefix, String name, String category,
        Severity defaultSeverity, boolean enabled, String description, String recommendation,
        List<String> commonFalsePositives) {
    public RepoBuddyRule { commonFalsePositives = commonFalsePositives == null ? List.of() : List.copyOf(commonFalsePositives); }
}
