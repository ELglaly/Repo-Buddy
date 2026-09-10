package com.repoinspector.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class IssueIds {
    private IssueIds() {}

    public static String create(RepoBuddyRule rule, String relativePath, String stableAnchor,
                                String message, int fallbackOffset) {
        String material = String.join("\u001f", rule.id(), normalizePath(relativePath),
                value(stableAnchor), value(message), value(stableAnchor).isEmpty() ? String.valueOf(fallbackOffset) : "");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            return "RB-" + rule.idPrefix() + "-" + HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    public static String normalizePath(String path) { return path == null ? "" : path.replace('\\', '/'); }
    private static String value(String value) { return value == null ? "" : value.trim(); }
}
