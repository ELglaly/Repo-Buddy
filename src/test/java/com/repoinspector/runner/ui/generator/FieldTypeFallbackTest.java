package com.repoinspector.runner.ui.generator;

import com.repoinspector.runner.model.ParameterDef;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FieldTypeFallbackTest {

    private final FieldTypeFallback fallback = new FieldTypeFallback();

    @Test
    void supports_alwaysTrue() {
        assertTrue(fallback.supports(new ParameterDef("x", "boolean")));
        assertTrue(fallback.supports(new ParameterDef("x", "int")));
        assertTrue(fallback.supports(new ParameterDef("x", "MyUnknownType")));
        assertTrue(fallback.supports(new ParameterDef("x", "String")));
    }

    @Test
    void generate_boolean_trueOrFalse() {
        for (int i = 0; i < 20; i++) {
            String result = fallback.generate(new ParameterDef("x", "boolean"));
            assertTrue("true".equals(result) || "false".equals(result),
                    "BOOLEAN must be true or false: " + result);
        }
    }

    @Test
    void generate_number_parseablePositiveInt() {
        for (int i = 0; i < 20; i++) {
            String result = fallback.generate(new ParameterDef("x", "int"));
            int val = Integer.parseInt(result);
            assertTrue(val >= 1 && val < DataPool.MAX_ID, "NUMBER out of range: " + val);
        }
    }

    @Test
    void generate_decimal_twoDecimalPlaces() {
        for (int i = 0; i < 10; i++) {
            String result = fallback.generate(new ParameterDef("x", "double"));
            assertTrue(result.matches("\\d+\\.\\d{2}"), "DECIMAL must be ##.## format: " + result);
        }
    }

    @Test
    void generate_text_nonEmptyWithSpace() {
        for (int i = 0; i < 10; i++) {
            String result = fallback.generate(new ParameterDef("x", "String"));
            assertFalse(result.isEmpty(), "TEXT must not be empty");
            assertTrue(result.contains(" "), "TEXT (full name) must contain space: " + result);
        }
    }

    @Test
    void generate_json_emptyObject() {
        String result = fallback.generate(new ParameterDef("x", "SomeEntity"));
        assertEquals("{}", result);
    }
}
