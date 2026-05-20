package com.repoinspector.runner.ui.generator;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DataPoolTest {

    @Test
    void firstNames_nonEmpty_noNulls() {
        assertPoolValid(DataPool.FIRST_NAMES, "FIRST_NAMES");
    }

    @Test
    void lastNames_nonEmpty_noNulls() {
        assertPoolValid(DataPool.LAST_NAMES, "LAST_NAMES");
    }

    @Test
    void emailDomains_nonEmpty_noNulls() {
        assertPoolValid(DataPool.EMAIL_DOMAINS, "EMAIL_DOMAINS");
        DataPool.EMAIL_DOMAINS.forEach(d ->
                assertTrue(d.contains("."), "domain must contain dot: " + d));
    }

    @Test
    void cities_nonEmpty_noNulls() {
        assertPoolValid(DataPool.CITIES, "CITIES");
    }

    @Test
    void countries_nonEmpty_noNulls() {
        assertPoolValid(DataPool.COUNTRIES, "COUNTRIES");
    }

    @Test
    void loremWords_nonEmpty_noNulls() {
        assertPoolValid(DataPool.LOREM_WORDS, "LOREM_WORDS");
    }

    @Test
    void statuses_nonEmpty_noNulls() {
        assertPoolValid(DataPool.STATUSES, "STATUSES");
    }

    @Test
    void httpMethods_containsExpected() {
        assertTrue(DataPool.HTTP_METHODS.contains("GET"));
        assertTrue(DataPool.HTTP_METHODS.contains("POST"));
        assertTrue(DataPool.HTTP_METHODS.contains("PUT"));
        assertTrue(DataPool.HTTP_METHODS.contains("DELETE"));
    }

    @Test
    void numericConstants_arePositive() {
        assertTrue(DataPool.MAX_ID > 0, "MAX_ID must be positive");
        assertTrue(DataPool.DATE_LOOKAHEAD_DAYS > 0, "DATE_LOOKAHEAD_DAYS must be positive");
        assertTrue(DataPool.MILLIS_PER_DAY > 0, "MILLIS_PER_DAY must be positive");
    }

    @Test
    void millisPerDay_correctValue() {
        assertEquals(86_400_000, DataPool.MILLIS_PER_DAY);
    }

    private static void assertPoolValid(List<String> pool, String name) {
        assertNotNull(pool, name + " must not be null");
        assertFalse(pool.isEmpty(), name + " must not be empty");
        pool.forEach(item -> assertNotNull(item, name + " must not contain nulls"));
    }
}
