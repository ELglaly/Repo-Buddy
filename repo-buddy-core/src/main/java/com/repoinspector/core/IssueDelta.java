package com.repoinspector.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Line-independent multiset comparison for semantic issue fingerprints. */
public final class IssueDelta {
    private IssueDelta() {}
    public record Result(List<IssueChangeStatus> baselineStatuses, List<IssueChangeStatus> currentStatuses, int resolvedCount) {
        public Result { baselineStatuses = List.copyOf(baselineStatuses); currentStatuses = List.copyOf(currentStatuses); }
    }
    public static Result compare(List<String> baseline, List<String> current) {
        Map<String, Integer> remaining = new HashMap<>();
        baseline.forEach(value -> remaining.merge(value, 1, Integer::sum));
        List<IssueChangeStatus> statuses = new ArrayList<>(current.size());
        for (String value : current) {
            int count = remaining.getOrDefault(value, 0);
            if (count > 0) {
                statuses.add(IssueChangeStatus.PRE_EXISTING);
                if (count == 1) remaining.remove(value); else remaining.put(value, count - 1);
            } else statuses.add(IssueChangeStatus.INTRODUCED);
        }
        Map<String, Integer> currentRemaining = new HashMap<>();
        current.forEach(value -> currentRemaining.merge(value, 1, Integer::sum));
        List<IssueChangeStatus> baselineStatuses = new ArrayList<>(baseline.size());
        int resolved = 0;
        for (String value : baseline) {
            int count = currentRemaining.getOrDefault(value, 0);
            if (count > 0) {
                baselineStatuses.add(IssueChangeStatus.PRE_EXISTING);
                if (count == 1) currentRemaining.remove(value); else currentRemaining.put(value, count - 1);
            } else { baselineStatuses.add(IssueChangeStatus.RESOLVED); resolved++; }
        }
        return new Result(baselineStatuses, statuses, resolved);
    }
}
