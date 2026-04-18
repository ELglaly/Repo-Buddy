package com.repoinspector.runner.ui.generator;

/**
 * Word-boundary segment matching for lowercased parameter names.
 *
 * <p>Plain {@link String#contains} produces false positives: for example,
 * {@code "lat"} would match {@code "platform"} or {@code "flatrate"}, and
 * {@code "id"} would match any word that happens to contain those letters consecutively.
 *
 * <p>This class checks boundaries at: string start, string end, underscore separator,
 * and camelCase prefix/suffix boundaries (visible after lowercasing, e.g.
 * {@code "userId"} → {@code "userid"} ends with {@code "id"}).
 *
 * @since 1.0
 */
final class SegmentMatcher {

    private SegmentMatcher() {}

    /**
     * Returns {@code true} when {@code lowercasedName} contains at least one of the
     * given {@code segments} at a word boundary.
     *
     * <p>Boundaries recognised:
     * <ul>
     *   <li>Exact equality: {@code "id"} matches parameter named {@code "id"}.</li>
     *   <li>Underscore prefix: {@code "lat_"} prefix matches {@code "lat_lng"}.</li>
     *   <li>Underscore suffix: {@code "_id"} suffix matches {@code "user_id"}.</li>
     *   <li>Underscore infix: {@code "_lat_"} infix matches {@code "from_lat_to"}.</li>
     *   <li>CamelCase suffix: {@code "id"} matches {@code "userid"} (from {@code "userId"}).</li>
     *   <li>CamelCase prefix: {@code "lat"} matches {@code "latlng"} (from {@code "latLng"}).</li>
     * </ul>
     *
     * @param lowercasedName parameter name already converted to lower case
     * @param segments       one or more target segments
     * @return {@code true} on the first match, {@code false} if none match
     * @since 1.0
     */
    static boolean has(String lowercasedName, String... segments) {
        for (String seg : segments) {
            if (matchesOne(lowercasedName, seg)) return true;
        }
        return false;
    }

    private static boolean matchesOne(String name, String seg) {
        if (name.equals(seg)) return true;
        if (name.startsWith(seg + "_")) return true;
        if (name.endsWith("_" + seg)) return true;
        if (name.contains("_" + seg + "_")) return true;
        // CamelCase suffix (e.g. "userid" ends with "id")
        if (name.length() > seg.length() && name.endsWith(seg)) return true;
        // CamelCase prefix (e.g. "latlng" starts with "lat")
        if (name.length() > seg.length() && name.startsWith(seg)) return true;
        return false;
    }
}
