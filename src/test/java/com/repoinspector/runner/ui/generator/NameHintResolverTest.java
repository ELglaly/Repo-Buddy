package com.repoinspector.runner.ui.generator;

import com.repoinspector.runner.model.ParameterDef;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NameHintResolverTest {

    private final NameHintResolver resolver = new NameHintResolver();

    // ── Email ────────────────────────────────────────────────────────────────

    @Test
    void email_exactName() {
        assertEmailPattern(NameHintResolver.resolve("email"));
    }

    @Test
    void email_camelCaseSuffix() {
        assertEmailPattern(NameHintResolver.resolve("useremail"));
    }

    @Test
    void email_underscorePrefix() {
        assertEmailPattern(NameHintResolver.resolve("email_address"));
    }

    // ── Names ────────────────────────────────────────────────────────────────

    @Test
    void firstname_inPool() {
        String result = NameHintResolver.resolve("firstname");
        assertNotNull(result);
        assertTrue(DataPool.FIRST_NAMES.contains(result), "expected first name from pool: " + result);
    }

    @Test
    void lastName_lowercased_inPool() {
        String result = NameHintResolver.resolve("lastname");
        assertNotNull(result);
        assertTrue(DataPool.LAST_NAMES.contains(result), "expected last name from pool: " + result);
    }

    @Test
    void fullname_containsSpace() {
        for (int i = 0; i < 10; i++) {
            String result = NameHintResolver.resolve("fullname");
            assertNotNull(result);
            assertTrue(result.contains(" "), "full name must contain space: " + result);
        }
    }

    // ── Username ─────────────────────────────────────────────────────────────

    @Test
    void username_endsWith_name_so_matchesFullNameRule() {
        // "username" ends with "name" (camelCase suffix), so the fullname rule fires before username.
        for (int i = 0; i < 10; i++) {
            String result = NameHintResolver.resolve("username");
            assertNotNull(result);
            assertTrue(result.contains(" "), "\"username\" triggers fullname rule (ends with 'name'): " + result);
        }
    }

    @Test
    void login_matchesUsernameRule() {
        // "login" is an exact username hint and doesn't match earlier name/email rules.
        for (int i = 0; i < 10; i++) {
            String result = NameHintResolver.resolve("login");
            assertNotNull(result);
            assertTrue(result.contains("_"), "login must match username rule (firstname_NNN): " + result);
            assertEquals(result, result.toLowerCase(), "login username must be lowercase: " + result);
        }
    }

    // ── Phone ────────────────────────────────────────────────────────────────

    @Test
    void phone_matchesPattern() {
        for (int i = 0; i < 10; i++) {
            String result = NameHintResolver.resolve("phone");
            assertNotNull(result);
            assertTrue(result.matches("\\+1-\\d{3}-\\d{3}-\\d{4}"),
                    "phone must match +1-XXX-XXX-XXXX: " + result);
        }
    }

    // ── Geo ──────────────────────────────────────────────────────────────────

    @Test
    void lat_inValidRange() {
        for (int i = 0; i < 20; i++) {
            String result = NameHintResolver.resolve("lat");
            assertNotNull(result);
            double lat = Double.parseDouble(result);
            assertTrue(lat >= -90 && lat <= 90, "lat out of range: " + lat);
        }
    }

    @Test
    void lng_inValidRange() {
        for (int i = 0; i < 20; i++) {
            String result = NameHintResolver.resolve("lng");
            assertNotNull(result);
            double lng = Double.parseDouble(result);
            assertTrue(lng >= -180 && lng <= 180, "lng out of range: " + lng);
        }
    }

    @Test
    void falsePositive_platform_doesNotMatchLat() {
        assertNull(NameHintResolver.resolve("platform"),
                "\"platform\" must not match the lat hint");
    }

    // ── ID / Numeric ─────────────────────────────────────────────────────────

    @Test
    void userId_numeric() {
        String result = NameHintResolver.resolve("userid");
        assertNotNull(result);
        int id = Integer.parseInt(result);
        assertTrue(id >= 1 && id < DataPool.MAX_ID);
    }

    @Test
    void id_exact_numeric() {
        String result = NameHintResolver.resolve("id");
        assertNotNull(result);
        assertDoesNotThrow(() -> Integer.parseInt(result));
    }

    @Test
    void age_inRange() {
        for (int i = 0; i < 20; i++) {
            String result = NameHintResolver.resolve("age");
            assertNotNull(result);
            int age = Integer.parseInt(result);
            assertTrue(age >= 18 && age <= 79, "age out of range: " + age);
        }
    }

    @Test
    void price_decimalWithDot() {
        for (int i = 0; i < 10; i++) {
            String result = NameHintResolver.resolve("price");
            assertNotNull(result);
            assertTrue(result.contains("."), "price must use dot separator: " + result);
            assertDoesNotThrow(() -> Double.parseDouble(result));
        }
    }

    @Test
    void page_endsWith_age_so_matchesAgeRule() {
        // "page" ends with "age" (camelCase suffix), so the age rule fires before the page rule.
        // The page→"0" rule is only reachable with a name like "paginateby" that doesn't end with "age".
        for (int i = 0; i < 20; i++) {
            String result = NameHintResolver.resolve("page");
            assertNotNull(result);
            int age = Integer.parseInt(result);
            assertTrue(age >= 18 && age <= 79, "\"page\" triggers age rule (ends with 'age'): " + age);
        }
    }

    // ── No match ─────────────────────────────────────────────────────────────

    @Test
    void unknownName_returnsNull() {
        assertNull(NameHintResolver.resolve("unknownxyzfield"));
    }

    @Test
    void falsePositive_validity_doesNotMatchId() {
        assertNull(NameHintResolver.resolve("validity"));
    }

    // ── supports() / generate() ──────────────────────────────────────────────

    @Test
    void supports_knownHint_returnsTrue() {
        assertTrue(resolver.supports(new ParameterDef("email", "String")));
        assertTrue(resolver.supports(new ParameterDef("userId", "Long")));
    }

    @Test
    void supports_unknownHint_returnsFalse() {
        assertFalse(resolver.supports(new ParameterDef("somethingUnknown", "String")));
    }

    @Test
    void generate_delegatesToResolve() {
        String result = resolver.generate(new ParameterDef("email", "String"));
        assertEmailPattern(result);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static void assertEmailPattern(String email) {
        assertNotNull(email);
        assertTrue(email.contains("@"), "email must contain @: " + email);
        String[] parts = email.split("@");
        assertEquals(2, parts.length);
        assertFalse(parts[0].isEmpty());
        assertTrue(parts[1].contains("."));
    }
}
