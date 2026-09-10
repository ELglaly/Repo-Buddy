package com.repoinspector.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CoreContractsTest {
    @Test void issuePageProvidesContinuationMetadata() {
        IssuePage first = new IssuePage("1", "scan-1", 60, 25, 0,
                java.util.Collections.nCopies(25, issue("1", "n-plus-one", Severity.HIGH, "src/A.java")));
        assertEquals(25, first.nextOffset());
        assertTrue(first.hasMore());

        IssuePage last = new IssuePage("1", "scan-1", 60, 25, 50,
                java.util.Collections.nCopies(10, issue("1", "n-plus-one", Severity.HIGH, "src/A.java")));
        assertNull(last.nextOffset());
        assertFalse(last.hasMore());
    }

    private static RepoBuddyIssue issue(String id, String rule, Severity severity, String path) {
        return new RepoBuddyIssue(id, rule, "DATABASE", severity, path, 10, 3,
                "message", "explanation", "fix");
    }

    @Test void severityOrderingIsExplicit() {
        assertTrue(Severity.HIGH.atLeast(Severity.MEDIUM));
        assertFalse(Severity.LOW.atLeast(Severity.MEDIUM));
    }

    @Test void stableIssueIdDoesNotExposeInput() {
        String id = IssueIds.create(RepoBuddyRules.N_PLUS_ONE, "src/Secret.java", "secretAnchor", "message", 20);
        assertTrue(id.matches("RB-NPLUS1-[0-9a-f]{12}"));
        assertFalse(id.contains("Secret"));
        assertEquals(id, IssueIds.create(RepoBuddyRules.N_PLUS_ONE, "src\\Secret.java", "secretAnchor", "message", 20));
    }

    @Test void filtersSeverityRuleFileAndPagination() {
        List<RepoBuddyIssue> issues = List.of(issue("1", "n-plus-one", Severity.HIGH, "src/A.java"),
                issue("2", "missing-pagination", Severity.MEDIUM, "src/B.java"),
                issue("3", "n-plus-one", Severity.HIGH, "src/A.java"));
        IssueQuery query = new IssueQuery(null, Severity.HIGH, "n-plus-one", "src\\A.java", 1, 1, null);
        assertEquals(2, IssueFilters.count(issues, query));
        assertEquals("3", IssueFilters.filter(issues, query).get(0).id());
    }

    @Test void jsonContractUsesCamelCaseAndEnums() {
        String json = RepoBuddyJson.gson(false).toJson(issue("RB-1", "n-plus-one", Severity.HIGH, "src/A.java"));
        assertTrue(json.contains("\"ruleId\":\"n-plus-one\""));
        assertTrue(json.contains("\"severity\":\"HIGH\""));
        assertFalse(json.contains("PsiElement"));
    }

    @Test void issueQueryEnforcesBounds() {
        assertThrows(IllegalArgumentException.class, () -> new IssueQuery(null, null, null, null, 201, 0, null));
        assertThrows(IllegalArgumentException.class, () -> new IssueQuery(null, null, null, null, 50, -1, null));
    }

    @Test void changeAwareContractsAreAdditiveAndUseExplicitScope() {
        assertEquals(AnalysisScope.CHANGED, AnalysisScope.parse("changed"));
        assertEquals(AnalysisScope.ALL, AnalysisScope.parse("ALL"));
        assertThrows(IllegalArgumentException.class, () -> AnalysisScope.parse("files"));
        ChangeAttribution attribution = new ChangeAttribution(IssueChangeStatus.INTRODUCED, "src/A.java", true,
                List.of(new ChangedRange("ADDED", null, null, 10, 12)), "A#load/0", false,
                ChangeAttributionReason.ISSUE_ON_CHANGED_CODE);
        RepoBuddyIssue issue = new RepoBuddyIssue("RB-1", "n-plus-one", "DATABASE", Severity.HIGH,
                "src/A.java", 11, 3, "message", "explanation", "fix", "A#load/0",
                IssueChangeStatus.INTRODUCED, attribution);
        String json = RepoBuddyJson.gson(false).toJson(issue);
        assertTrue(json.contains("\"changeStatus\":\"INTRODUCED\""));
        assertTrue(json.contains("\"directlyTouched\":true"));
    }

    @Test void issueDeltaUsesSemanticFingerprintsAsAMultiset() {
        IssueDelta.Result movedLine = IssueDelta.compare(List.of("same-issue"), List.of("same-issue"));
        assertEquals(List.of(IssueChangeStatus.PRE_EXISTING), movedLine.currentStatuses());
        assertEquals(List.of(IssueChangeStatus.PRE_EXISTING), movedLine.baselineStatuses());
        assertEquals(0, movedLine.resolvedCount());

        IssueDelta.Result mixed = IssueDelta.compare(List.of("old", "duplicate", "duplicate"),
                List.of("old", "new", "duplicate"));
        assertEquals(List.of(IssueChangeStatus.PRE_EXISTING, IssueChangeStatus.INTRODUCED,
                IssueChangeStatus.PRE_EXISTING), mixed.currentStatuses());
        assertEquals(1, mixed.resolvedCount());
        assertEquals(IssueChangeStatus.RESOLVED, mixed.baselineStatuses().get(2));
    }
}
