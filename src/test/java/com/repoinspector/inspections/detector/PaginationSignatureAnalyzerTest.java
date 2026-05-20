package com.repoinspector.inspections.detector;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PaginationSignatureAnalyzerTest {

    // ── isUnboundedCollection() ───────────────────────────────────────────────

    @Test
    void isUnboundedCollection_trueForJdkCollections() {
        assertTrue(PaginationSignatureAnalyzer.isUnboundedCollection("java.util.List"));
        assertTrue(PaginationSignatureAnalyzer.isUnboundedCollection("java.util.Set"));
        assertTrue(PaginationSignatureAnalyzer.isUnboundedCollection("java.util.Collection"));
        assertTrue(PaginationSignatureAnalyzer.isUnboundedCollection("java.lang.Iterable"));
        assertTrue(PaginationSignatureAnalyzer.isUnboundedCollection("java.util.stream.Stream"));
    }

    @Test
    void isUnboundedCollection_falseForPageAndSlice() {
        assertFalse(PaginationSignatureAnalyzer.isUnboundedCollection("org.springframework.data.domain.Page"));
        assertFalse(PaginationSignatureAnalyzer.isUnboundedCollection("org.springframework.data.domain.Slice"));
    }

    @Test
    void isUnboundedCollection_falseForEntityType() {
        assertFalse(PaginationSignatureAnalyzer.isUnboundedCollection("com.example.User"));
    }

    @Test
    void isUnboundedCollection_nullSafe() {
        assertFalse(PaginationSignatureAnalyzer.isUnboundedCollection(null));
    }

    // ── looksLikeFindAll() ────────────────────────────────────────────────────

    @Test
    void looksLikeFindAll_trueForAllSegment() {
        assertTrue(PaginationSignatureAnalyzer.looksLikeFindAll("findAll"));
        assertTrue(PaginationSignatureAnalyzer.looksLikeFindAll("findAllByStatus"));
        assertTrue(PaginationSignatureAnalyzer.looksLikeFindAll("getAllUsers"));
    }

    @Test
    void looksLikeFindAll_falseForSpecificFinders() {
        assertFalse(PaginationSignatureAnalyzer.looksLikeFindAll("findByStatus"));
    }

    @Test
    void looksLikeFindAll_falseForLowercaseAllSubstring() {
        assertFalse(PaginationSignatureAnalyzer.looksLikeFindAll("findSmallItems"));
        assertFalse(PaginationSignatureAnalyzer.looksLikeFindAll("findOverallStats"));
    }

    @Test
    void looksLikeFindAll_falseWhenAllIsPrefixOfWord() {
        // "Allowed" starts with "All" but continues with a lowercase letter
        assertFalse(PaginationSignatureAnalyzer.looksLikeFindAll("findAllowedUsers"));
    }

    @Test
    void looksLikeFindAll_nullSafe() {
        assertFalse(PaginationSignatureAnalyzer.looksLikeFindAll(null));
    }

    // ── isLimitedByName() ─────────────────────────────────────────────────────

    @Test
    void isLimitedByName_trueForFirstAndTop() {
        assertTrue(PaginationSignatureAnalyzer.isLimitedByName("findFirstByName"));
        assertTrue(PaginationSignatureAnalyzer.isLimitedByName("findTopByAge"));
        assertTrue(PaginationSignatureAnalyzer.isLimitedByName("findTop10ByAge"));
        assertTrue(PaginationSignatureAnalyzer.isLimitedByName("queryFirst5ByX"));
    }

    @Test
    void isLimitedByName_falseForTopicSubstring() {
        assertFalse(PaginationSignatureAnalyzer.isLimitedByName("findByTopic"));
    }

    @Test
    void isLimitedByName_falseForPlainFindAll() {
        assertFalse(PaginationSignatureAnalyzer.isLimitedByName("findAllByStatus"));
    }

    @Test
    void isLimitedByName_nullSafe() {
        assertFalse(PaginationSignatureAnalyzer.isLimitedByName(null));
    }
}
