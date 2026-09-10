package com.repoinspector.core;
import java.util.List;
public record ChangeAttribution(IssueChangeStatus status, String changedFile, boolean directlyTouched,
        List<ChangedRange> relatedChangedRanges, String affectedSymbol,
        boolean baselineFingerprintPresent, ChangeAttributionReason reason) {
    public ChangeAttribution { relatedChangedRanges = relatedChangedRanges == null ? List.of() : List.copyOf(relatedChangedRanges); }
}
