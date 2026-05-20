package com.repoinspector.runner.ui.generator;

import com.repoinspector.runner.model.ParameterDef;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TypeNameResolverTest {

    private final TypeNameResolver resolver = new TypeNameResolver();

    // ── supports() ───────────────────────────────────────────────────────────

    @Test
    void supports_pageable_true() {
        assertTrue(resolver.supports(new ParameterDef("p", "Pageable")));
    }

    @Test
    void supports_string_false() {
        assertFalse(resolver.supports(new ParameterDef("s", "String")));
    }

    @Test
    void supports_unknownType_false() {
        assertFalse(resolver.supports(new ParameterDef("x", "MyCustomType")));
    }

    // ── Pagination ───────────────────────────────────────────────────────────

    @Test
    void generate_pageable_containsPageAndSize() {
        String result = resolver.generate(new ParameterDef("p", "Pageable"));
        assertTrue(result.contains("\"page\":0"), "Pageable must contain page:0 — got: " + result);
        assertTrue(result.contains("\"size\":10"), "Pageable must contain size:10 — got: " + result);
        assertTrue(result.contains("\"orders\""), "Pageable must contain orders — got: " + result);
    }

    @Test
    void generate_sort_containsOrders() {
        String result = resolver.generate(new ParameterDef("s", "Sort"));
        assertTrue(result.contains("\"orders\""), "Sort must contain orders — got: " + result);
    }

    // ── UUID ─────────────────────────────────────────────────────────────────

    @Test
    void generate_uuid_validFormat() {
        for (int i = 0; i < 10; i++) {
            String result = resolver.generate(new ParameterDef("u", "UUID"));
            assertDoesNotThrow(() -> UUID.fromString(result), "UUID parse failed: " + result);
        }
    }

    // ── Date / Time ──────────────────────────────────────────────────────────

    @Test
    void generate_localDate_parseable() {
        for (int i = 0; i < 10; i++) {
            String result = resolver.generate(new ParameterDef("d", "LocalDate"));
            assertDoesNotThrow(() -> LocalDate.parse(result), "LocalDate parse failed: " + result);
        }
    }

    @Test
    void generate_localDateTime_parseable() {
        for (int i = 0; i < 10; i++) {
            String result = resolver.generate(new ParameterDef("dt", "LocalDateTime"));
            assertDoesNotThrow(() -> LocalDateTime.parse(result), "LocalDateTime parse failed: " + result);
        }
    }

    @Test
    void generate_instant_parseable() {
        String result = resolver.generate(new ParameterDef("t", "Instant"));
        assertFalse(result.isEmpty());
        assertTrue(result.contains("Z") || result.matches("\\d{4}-\\d{2}-\\d{2}T.*"),
                "Instant should be ISO format: " + result);
    }

    // ── Numeric types ────────────────────────────────────────────────────────

    @Test
    void generate_int_parseable() {
        String result = resolver.generate(new ParameterDef("n", "int"));
        assertDoesNotThrow(() -> Integer.parseInt(result), "int parse failed: " + result);
    }

    @Test
    void generate_long_parseable() {
        String result = resolver.generate(new ParameterDef("n", "long"));
        assertDoesNotThrow(() -> Long.parseLong(result), "long parse failed: " + result);
    }

    @Test
    void generate_double_parseable() {
        String result = resolver.generate(new ParameterDef("n", "double"));
        assertDoesNotThrow(() -> Double.parseDouble(result), "double parse failed: " + result);
    }

    @Test
    void generate_bigDecimal_parseable() {
        String result = resolver.generate(new ParameterDef("n", "BigDecimal"));
        assertTrue(result.matches("-?\\d+\\.\\d+"), "BigDecimal format: " + result);
    }

    // ── Boolean ──────────────────────────────────────────────────────────────

    @Test
    void generate_boolean_trueOrFalse() {
        for (int i = 0; i < 20; i++) {
            String result = resolver.generate(new ParameterDef("n", "boolean"));
            assertTrue("true".equals(result) || "false".equals(result),
                    "boolean must be true or false: " + result);
        }
    }

    // ── Char ─────────────────────────────────────────────────────────────────

    @Test
    void generate_char_returnsA() {
        assertEquals("A", resolver.generate(new ParameterDef("n", "char")));
    }

    // ── Optional unwrapping ──────────────────────────────────────────────────

    @Test
    void generate_optionalLong_unwrapsToLong() {
        String result = resolver.generate(new ParameterDef("n", "Optional<Long>"));
        assertDoesNotThrow(() -> Long.parseLong(result), "Optional<Long> should unwrap to long: " + result);
    }

    @Test
    void generate_optionalOptionalLong_doubleUnwrap() {
        String result = resolver.generate(new ParameterDef("n", "Optional<Optional<Long>>"));
        assertDoesNotThrow(() -> Long.parseLong(result),
                "Optional<Optional<Long>> should double-unwrap to long: " + result);
    }

    // ── Class<T> ─────────────────────────────────────────────────────────────

    @Test
    void generate_classWithFqn_returnsFqn() {
        String result = resolver.generate(new ParameterDef("n", "Class<com.example.User>"));
        assertEquals("com.example.User", result);
    }

    @Test
    void generate_classRaw_returnsPlaceholder() {
        String result = resolver.generate(new ParameterDef("n", "Class"));
        assertEquals("com.example.YourProjectionInterface", result);
    }

    @Test
    void generate_classWildcard_returnsPlaceholder() {
        String result = resolver.generate(new ParameterDef("n", "Class<?>"));
        assertEquals("com.example.YourProjectionInterface", result);
    }

    // ── Collections ──────────────────────────────────────────────────────────

    @Test
    void generate_listString_jsonArray() {
        String result = resolver.generate(new ParameterDef("n", "List<String>"));
        assertTrue(result.startsWith("[") && result.endsWith("]"),
                "List<String> must be JSON array: " + result);
    }

    @Test
    void generate_setLong_jsonArray() {
        String result = resolver.generate(new ParameterDef("n", "Set<Long>"));
        assertTrue(result.startsWith("[") && result.endsWith("]"),
                "Set<Long> must be JSON array: " + result);
    }

    @Test
    void generate_listUuid_jsonArray() {
        String result = resolver.generate(new ParameterDef("n", "List<UUID>"));
        assertTrue(result.startsWith("[") && result.endsWith("]"),
                "List<UUID> must be JSON array: " + result);
    }

    // ── Maps ─────────────────────────────────────────────────────────────────

    @Test
    void generate_mapStringString_jsonObject() {
        String result = resolver.generate(new ParameterDef("n", "Map<String, String>"));
        assertTrue(result.startsWith("{") && result.endsWith("}"),
                "Map must be JSON object: " + result);
    }

    @Test
    void generate_mapRaw_jsonObject() {
        String result = resolver.generate(new ParameterDef("n", "Map"));
        assertTrue(result.startsWith("{") && result.endsWith("}"),
                "Map (raw) must be JSON object: " + result);
    }

    // ── Name-refined numerics ────────────────────────────────────────────────

    @Test
    void generate_latDouble_inRange() {
        for (int i = 0; i < 20; i++) {
            double lat = Double.parseDouble(resolver.generate(new ParameterDef("lat", "double")));
            assertTrue(lat >= -90 && lat <= 90, "lat out of range: " + lat);
        }
    }

    @Test
    void generate_ageInt_inRange() {
        for (int i = 0; i < 20; i++) {
            int age = Integer.parseInt(resolver.generate(new ParameterDef("age", "int")));
            assertTrue(age >= 18 && age <= 79, "age out of range: " + age);
        }
    }

    @Test
    void generate_pageInt_matchesAgeRule() {
        // "page" ends with "age" (camelCase suffix) so numericByName fires the age rule before page.
        for (int i = 0; i < 20; i++) {
            int val = Integer.parseInt(resolver.generate(new ParameterDef("page", "int")));
            assertTrue(val >= 18 && val <= 79, "\"page\" int triggers age rule: " + val);
        }
    }
}
