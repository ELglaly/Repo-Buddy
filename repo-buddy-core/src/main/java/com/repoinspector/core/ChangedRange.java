package com.repoinspector.core;
public record ChangedRange(String kind, Integer beforeStartLine, Integer beforeEndLine,
        Integer afterStartLine, Integer afterEndLine) {}
