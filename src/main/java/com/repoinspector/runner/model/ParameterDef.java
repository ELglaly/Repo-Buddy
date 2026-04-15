package com.repoinspector.runner.model;

/**
 * Describes one parameter of a repository method as derived from PSI.
 *
 * @param name     parameter name (from source, e.g. "userId"; may be "arg0" in bytecode-only projects)
 * @param typeName presentable type text from PSI, e.g. "Long", "String", "Optional&lt;User&gt;"
 */
public record ParameterDef(String name, String typeName) {

    /** Broad input-field category used by the UI to pick the right widget. */
    public enum FieldType { TEXT, NUMBER, DECIMAL, BOOLEAN, JSON }

    /** Maps the PSI type name to the appropriate UI input category. */
    public FieldType fieldType() {
        return switch (typeName) {
            case "boolean", "Boolean"                          -> FieldType.BOOLEAN;
            case "int", "Integer", "long", "Long",
                 "short", "Short", "byte", "Byte"             -> FieldType.NUMBER;
            case "double", "Double", "float", "Float"         -> FieldType.DECIMAL;
            case "String"                                      -> FieldType.TEXT;
            default                                            -> FieldType.JSON;
        };
    }
}
