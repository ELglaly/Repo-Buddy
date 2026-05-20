package com.repoinspector.inspections.detector;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure (PSI-free) analysis of a Spring Data {@code @Query} value string.
 *
 * <p>Kept free of any IntelliJ PSI dependency so it can be unit-tested in
 * isolation and reused by inspections that only need the textual shape of a
 * JPQL / native query.
 */
public final class QueryStringAnalyzer {

    private QueryStringAnalyzer() {}

    /**
     * Named bind parameters ({@code :name}).
     *
     * <p>A leading {@code :} preceded by another {@code :} is treated as a cast
     * (e.g. PostgreSQL {@code value::text}) and ignored. SpEL expressions
     * ({@code :#{...}}) are excluded because {@code #} is not a word character.
     */
    private static final Pattern NAMED_PARAM = Pattern.compile("(?<!:):([A-Za-z_]\\w*)");

    /** Positional bind parameters ({@code ?1}). */
    private static final Pattern POSITIONAL_PARAM = Pattern.compile("\\?(\\d+)");

    /** {@code LIMIT} keyword as a standalone word. */
    private static final Pattern LIMIT = Pattern.compile("\\bLIMIT\\b", Pattern.CASE_INSENSITIVE);

    public static Set<String> namedParameters(String query) {
        Set<String> result = new LinkedHashSet<>();
        if (query == null) return result;
        Matcher m = NAMED_PARAM.matcher(query);
        while (m.find()) {
            result.add(m.group(1));
        }
        return result;
    }

    public static Set<Integer> positionalParameters(String query) {
        Set<Integer> result = new LinkedHashSet<>();
        if (query == null) return result;
        Matcher m = POSITIONAL_PARAM.matcher(query);
        while (m.find()) {
            result.add(Integer.parseInt(m.group(1)));
        }
        return result;
    }

    /** True if the query embeds a SpEL expression ({@code #{...}} or {@code :#{...}}). */
    public static boolean containsSpel(String query) {
        return query != null && query.contains("#{");
    }

    /** True if the query already bounds its result set with a {@code LIMIT} clause. */
    public static boolean containsLimit(String query) {
        return query != null && LIMIT.matcher(query).find();
    }
}
