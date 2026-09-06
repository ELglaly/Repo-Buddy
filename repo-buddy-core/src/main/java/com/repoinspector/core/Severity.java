package com.repoinspector.core;

public enum Severity {
    INFO(0), LOW(1), MEDIUM(2), HIGH(3);
    private final int rank;
    Severity(int rank) { this.rank = rank; }
    public boolean atLeast(Severity other) { return rank >= other.rank; }
}
