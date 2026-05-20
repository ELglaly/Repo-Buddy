package com.repoinspector.runner.ui.generator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class ValueBuildersTest {

    @Test
    void randomEmail_matchesEmailPattern() {
        for (int i = 0; i < 20; i++) {
            String email = ValueBuilders.randomEmail();
            assertTrue(email.contains("@"), "email must contain @: " + email);
            assertTrue(email.contains("."), "email must contain dot: " + email);
            String[] parts = email.split("@");
            assertEquals(2, parts.length);
            assertFalse(parts[0].isEmpty());
            assertFalse(parts[1].isEmpty());
        }
    }

    @Test
    void randomPhone_matchesPattern() {
        for (int i = 0; i < 20; i++) {
            String phone = ValueBuilders.randomPhone();
            assertTrue(phone.matches("\\+1-\\d{3}-\\d{3}-\\d{4}"),
                    "phone must match +1-XXX-XXX-XXXX: " + phone);
        }
    }

    @Test
    void randomZip_isFiveDigits() {
        for (int i = 0; i < 20; i++) {
            String zip = ValueBuilders.randomZip();
            assertTrue(zip.matches("\\d{5}"), "zip must be 5 digits: " + zip);
        }
    }

    @Test
    void randomHexColor_matchesPattern() {
        for (int i = 0; i < 20; i++) {
            String color = ValueBuilders.randomHexColor();
            assertTrue(color.matches("#[0-9a-f]{6}"),
                    "hex color must match #rrggbb: " + color);
        }
    }

    @Test
    void randomCode_correctLength() {
        for (int len : new int[]{1, 8, 12, 32}) {
            String code = ValueBuilders.randomCode(len);
            assertEquals(len, code.length(), "code length mismatch for " + len);
            assertTrue(code.matches("[A-Za-z0-9]+"), "code must be alphanumeric: " + code);
        }
    }

    @Test
    void randomCode_zeroLength_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> ValueBuilders.randomCode(0));
    }

    @Test
    void randomCode_negativeLength_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> ValueBuilders.randomCode(-1));
    }

    @Test
    void capitalise_emptyString() {
        assertEquals("", ValueBuilders.capitalise(""));
    }

    @Test
    void capitalise_lowercaseWord() {
        assertEquals("Hello", ValueBuilders.capitalise("hello"));
    }

    @Test
    void capitalise_alreadyUppercase() {
        assertEquals("HELLO", ValueBuilders.capitalise("HELLO"));
    }

    @Test
    void capitalise_singleChar() {
        assertEquals("A", ValueBuilders.capitalise("a"));
    }

    @Test
    void randomFullName_containsSpace() {
        for (int i = 0; i < 20; i++) {
            String name = ValueBuilders.randomFullName();
            String[] parts = name.split(" ");
            assertEquals(2, parts.length, "full name must have exactly 2 parts: " + name);
            assertTrue(DataPool.FIRST_NAMES.contains(parts[0]),
                    "first name not in pool: " + parts[0]);
            assertTrue(DataPool.LAST_NAMES.contains(parts[1]),
                    "last name not in pool: " + parts[1]);
        }
    }

    @Test
    void randomUsername_isLowercaseWithNumericSuffix() {
        for (int i = 0; i < 20; i++) {
            String username = ValueBuilders.randomUsername();
            assertTrue(username.contains("_"), "username must contain underscore: " + username);
            String[] parts = username.split("_");
            assertTrue(parts.length >= 2);
            assertTrue(parts[parts.length - 1].matches("\\d+"),
                    "username suffix must be numeric: " + username);
            assertEquals(username, username.toLowerCase(), "username must be lowercase: " + username);
        }
    }

    @Test
    void randomStreet_endsWithSt() {
        for (int i = 0; i < 10; i++) {
            String street = ValueBuilders.randomStreet();
            assertTrue(street.endsWith(" St"), "street must end with ' St': " + street);
        }
    }

    @Test
    void randomSentence_endsWithPeriod_startsWithCapital_validWordCount() {
        for (int i = 0; i < 20; i++) {
            String sentence = ValueBuilders.randomSentence();
            assertTrue(sentence.endsWith("."), "sentence must end with '.': " + sentence);
            assertTrue(Character.isUpperCase(sentence.charAt(0)),
                    "sentence must start with uppercase: " + sentence);
            long wordCount = sentence.replace(".", "").split(" ").length;
            assertTrue(wordCount >= 5 && wordCount <= 12,
                    "sentence word count must be 5-12, got " + wordCount + ": " + sentence);
        }
    }
}
