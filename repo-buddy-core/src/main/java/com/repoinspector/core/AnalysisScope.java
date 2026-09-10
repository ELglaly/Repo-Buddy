package com.repoinspector.core;

public enum AnalysisScope {
    ALL, CHANGED;

    public static AnalysisScope parse(String value) {
        if (value == null) throw new IllegalArgumentException("scope is required");
        try { return valueOf(value.trim().toUpperCase()); }
        catch (IllegalArgumentException error) { throw new IllegalArgumentException("scope must be all or changed"); }
    }
}
