package com.repoinspector.runner.model;

/**
 * Describes one parameter of a repository method as derived from PSI.
 *
 * <p>Pure data record — classification of the type name into a UI widget category is
 * delegated to {@link com.repoinspector.runner.service.ParameterTypeClassifier} (SRP).
 *
 * @param name     parameter name (from source, e.g. "userId"; may be "arg0" in bytecode-only projects)
 * @param typeName presentable type text from PSI, e.g. "Long", "String", "Optional&lt;User&gt;"
 */
public record ParameterDef(String name, String typeName) {

    /** Broad input-field category used by the UI to pick the right widget. */
    public enum FieldType { TEXT, NUMBER, DECIMAL, BOOLEAN, JSON }
}
