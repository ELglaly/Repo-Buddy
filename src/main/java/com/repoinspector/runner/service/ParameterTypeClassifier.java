package com.repoinspector.runner.service;

import com.repoinspector.runner.model.ParameterDef;

/**
 * Maps a PSI type name to the appropriate UI input category ({@link ParameterDef.FieldType}).
 *
 * <p>Extracted from {@code ParameterDef.fieldType()} to respect SRP — a record should be
 * a pure data container; classification logic belongs in its own class.
 */
public final class ParameterTypeClassifier {

    private ParameterTypeClassifier() {}

    /**
     * Classifies {@code typeName} (the presentable text from PSI, e.g. {@code "Long"},
     * {@code "Optional<User>"}) to a broad UI field category.
     *
     * @param typeName presentable type text from PSI
     * @return the appropriate {@link ParameterDef.FieldType}
     */
    public static ParameterDef.FieldType classify(String typeName) {
        return switch (typeName) {
            case "boolean", "Boolean"                                -> ParameterDef.FieldType.BOOLEAN;
            case "int", "Integer", "long", "Long",
                 "short", "Short", "byte", "Byte"                   -> ParameterDef.FieldType.NUMBER;
            case "double", "Double", "float", "Float", "BigDecimal" -> ParameterDef.FieldType.DECIMAL;
            case "String", "UUID", "LocalDate", "LocalDateTime",
                 "ZonedDateTime", "OffsetDateTime", "Instant"       -> ParameterDef.FieldType.TEXT;
            default                                                  -> ParameterDef.FieldType.JSON;
        };
    }
}
