package com.repoinspector.runner.service;

import com.repoinspector.runner.model.ParameterDef;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ParameterTypeClassifierTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            // BOOLEAN
            "boolean,        BOOLEAN",
            "Boolean,        BOOLEAN",
            // NUMBER
            "int,            NUMBER",
            "Integer,        NUMBER",
            "long,           NUMBER",
            "Long,           NUMBER",
            "short,          NUMBER",
            "Short,          NUMBER",
            "byte,           NUMBER",
            "Byte,           NUMBER",
            // DECIMAL
            "double,         DECIMAL",
            "Double,         DECIMAL",
            "float,          DECIMAL",
            "Float,          DECIMAL",
            "BigDecimal,     DECIMAL",
            // TEXT
            "String,         TEXT",
            "UUID,           TEXT",
            "LocalDate,      TEXT",
            "LocalDateTime,  TEXT",
            "ZonedDateTime,  TEXT",
            "OffsetDateTime, TEXT",
            "Instant,        TEXT",
            // JSON (default)
            "Optional<User>, JSON",
            "MyEntity,       JSON",
            "List<User>,     JSON",
            "Object,         JSON",
    })
    void classify_mapsTypeToExpectedFieldType(String typeName, ParameterDef.FieldType expected) {
        assertEquals(expected, ParameterTypeClassifier.classify(typeName.trim()));
    }
}
