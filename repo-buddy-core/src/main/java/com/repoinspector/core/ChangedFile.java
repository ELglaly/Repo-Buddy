package com.repoinspector.core;
import java.util.List;
public record ChangedFile(String filePath, String beforePath, GitChangeType changeType,
        String beforeRevision, String afterRevision, List<ChangedRange> changedRanges,
        List<ChangedRange> addedRanges, List<ChangedRange> modifiedRanges, List<ChangedRange> deletedRanges) {
    public ChangedFile {
        changedRanges = List.copyOf(changedRanges); addedRanges = List.copyOf(addedRanges);
        modifiedRanges = List.copyOf(modifiedRanges); deletedRanges = List.copyOf(deletedRanges);
    }
}
