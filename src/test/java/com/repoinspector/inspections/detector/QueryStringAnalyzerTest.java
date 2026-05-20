package com.repoinspector.inspections.detector;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class QueryStringAnalyzerTest {

    // ── namedParameters() ────────────────────────────────────────────────────

    @Test
    void namedParameters_singleParam() {
        assertEquals(Set.of("name"),
                QueryStringAnalyzer.namedParameters("SELECT u FROM User u WHERE u.name = :name"));
    }

    @Test
    void namedParameters_multipleParams() {
        assertEquals(Set.of("first", "last"),
                QueryStringAnalyzer.namedParameters(
                        "SELECT u FROM User u WHERE u.first = :first AND u.last = :last"));
    }

    @Test
    void namedParameters_duplicateParamCountedOnce() {
        assertEquals(Set.of("id"),
                QueryStringAnalyzer.namedParameters(
                        "SELECT u FROM User u WHERE u.a = :id OR u.b = :id"));
    }

    @Test
    void namedParameters_ignoresSpelExpression() {
        // :#{...} is SpEL, not a named bind parameter
        assertEquals(Set.of(),
                QueryStringAnalyzer.namedParameters(
                        "SELECT u FROM #{#entityName} u WHERE u.x = :#{#user.id}"));
    }

    @Test
    void namedParameters_ignoresPostgresCast() {
        // value::text in a native query must not register "text" as a param
        assertEquals(Set.of("name"),
                QueryStringAnalyzer.namedParameters(
                        "SELECT * FROM users WHERE name = :name AND tag = data::text"));
    }

    @Test
    void namedParameters_noParams() {
        assertEquals(Set.of(),
                QueryStringAnalyzer.namedParameters("SELECT u FROM User u"));
    }

    @Test
    void namedParameters_nullSafe() {
        assertEquals(Set.of(), QueryStringAnalyzer.namedParameters(null));
    }

    // ── positionalParameters() ───────────────────────────────────────────────

    @Test
    void positionalParameters_extractsIndexes() {
        assertEquals(Set.of(1, 2),
                QueryStringAnalyzer.positionalParameters(
                        "SELECT u FROM User u WHERE u.a = ?1 AND u.b = ?2"));
    }

    @Test
    void positionalParameters_noneFound() {
        assertEquals(Set.of(),
                QueryStringAnalyzer.positionalParameters("SELECT u FROM User u WHERE u.name = :name"));
    }

    @Test
    void positionalParameters_nullSafe() {
        assertEquals(Set.of(), QueryStringAnalyzer.positionalParameters(null));
    }

    // ── containsSpel() ───────────────────────────────────────────────────────

    @Test
    void containsSpel_detectsExpression() {
        assertTrue(QueryStringAnalyzer.containsSpel("SELECT u FROM #{#entityName} u"));
        assertTrue(QueryStringAnalyzer.containsSpel("... WHERE u.id = :#{#user.id}"));
    }

    @Test
    void containsSpel_falseForPlainQuery() {
        assertFalse(QueryStringAnalyzer.containsSpel("SELECT u FROM User u WHERE u.id = :id"));
    }

    @Test
    void containsSpel_nullSafe() {
        assertFalse(QueryStringAnalyzer.containsSpel(null));
    }

    // ── containsLimit() ──────────────────────────────────────────────────────

    @Test
    void containsLimit_detectsLimitKeyword() {
        assertTrue(QueryStringAnalyzer.containsLimit("SELECT * FROM users LIMIT 10"));
        assertTrue(QueryStringAnalyzer.containsLimit("select * from users limit 5"));
    }

    @Test
    void containsLimit_falseWhenAbsent() {
        assertFalse(QueryStringAnalyzer.containsLimit("SELECT u FROM User u"));
    }

    @Test
    void containsLimit_doesNotMatchSubstring() {
        assertFalse(QueryStringAnalyzer.containsLimit("SELECT u FROM RateLimited u"));
    }

    @Test
    void containsLimit_nullSafe() {
        assertFalse(QueryStringAnalyzer.containsLimit(null));
    }
}
