package com.repoinspector.constants;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class SqlKeywordsTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT", "FROM", "WHERE", "JOIN", "LEFT", "RIGHT", "INNER",
            "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE",
            "ORDER", "BY", "GROUP", "HAVING", "LIMIT", "OFFSET",
            "AND", "OR", "NOT", "IN", "IS", "NULL", "LIKE",
            "COUNT", "SUM", "AVG", "MIN", "MAX"
    })
    void containsExpectedKeyword(String keyword) {
        assertTrue(SqlKeywords.ALL.contains(keyword), "Missing keyword: " + keyword);
    }

    @Test
    void set_isImmutable() {
        assertThrows(UnsupportedOperationException.class, () -> SqlKeywords.ALL.add("CUSTOM"));
    }

    @Test
    void set_nonEmpty() {
        assertFalse(SqlKeywords.ALL.isEmpty());
    }
}
