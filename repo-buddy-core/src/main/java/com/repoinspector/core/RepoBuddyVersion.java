package com.repoinspector.core;

/** Resolves the product version from build metadata without hard-coding it in command implementations. */
public final class RepoBuddyVersion {
    private RepoBuddyVersion() {}

    public static String current() {
        String override = System.getProperty("repobuddy.version");
        if (override != null && !override.isBlank()) return override;
        String implementation = RepoBuddyVersion.class.getPackage().getImplementationVersion();
        return implementation == null || implementation.isBlank() ? "development" : implementation;
    }
}
