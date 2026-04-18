package com.repoinspector.runner.model;

import java.util.List;

/**
 * Describes one parameter of a repository method as derived from PSI.
 *
 * <p>Pure data record — classification of the type name into a UI widget category is
 * delegated to {@link com.repoinspector.runner.service.ParameterTypeClassifier} (SRP).
 *
 * @param name          parameter name (from source, e.g. "userId"; may be "arg0" in bytecode-only projects)
 * @param typeName      presentable type text from PSI, e.g. "Long", "String", "Optional&lt;User&gt;"
 * @param enumConstants immutable list of declared enum constant names when the type is an enum;
 *                      empty list for all non-enum types
 */
public record ParameterDef(String name, String typeName, List<String> enumConstants) {

    /** Broad input-field category used by the UI to pick the right widget. */
    public enum FieldType { TEXT, NUMBER, DECIMAL, BOOLEAN, JSON }

    /**
     * Convenience constructor for non-enum parameters.
     *
     * @param name     parameter name
     * @param typeName presentable type text from PSI
     */
    public ParameterDef(String name, String typeName) {
        this(name, typeName, List.of());
    }
}
