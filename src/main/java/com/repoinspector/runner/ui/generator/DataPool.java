package com.repoinspector.runner.ui.generator;

import java.util.List;

/**
 * Immutable sampling pools and numeric constants used across generation strategies.
 *
 * <p>All {@code List} fields are created via {@link List#of}, making them structurally
 * immutable and safely published at class-loading time via {@code static final} initializers.
 *
 * @since 1.0
 */
public final class DataPool {

    /**
     * Maximum number of days to look back when randomising date/time values.
     * Produces dates spread across roughly one calendar year.
     */
    public static final int DATE_LOOKAHEAD_DAYS = 365;

    /**
     * Milliseconds in one day. Used as the upper bound (exclusive) when computing
     * a random millisecond offset for {@code Timestamp} generation.
     */
    public static final int MILLIS_PER_DAY = 86_400_000;

    /**
     * Exclusive upper bound for generic numeric ID generation when no type-specific
     * range is available.
     */
    public static final int MAX_ID = 10_000;

    /** Sample first names for email, username, and full-name generation. */
    public static final List<String> FIRST_NAMES = List.of(
            "Alice", "Bob", "Carol", "David", "Emma", "Frank",
            "Grace", "Henry", "Isabelle", "Jack", "Kate", "Liam");

    /** Sample last names for email and full-name generation. */
    public static final List<String> LAST_NAMES = List.of(
            "Smith", "Johnson", "Williams", "Brown", "Jones",
            "Garcia", "Miller", "Davis", "Wilson", "Taylor");

    /** Sample email domains used as the host part of generated email addresses. */
    public static final List<String> EMAIL_DOMAINS = List.of(
            "example.com", "mail.dev", "test.io", "demo.org", "sample.net");

    /** Sample city names for address and location generation. */
    public static final List<String> CITIES = List.of(
            "New York", "London", "Paris", "Berlin", "Tokyo",
            "Sydney", "Toronto", "Amsterdam", "Singapore", "Dubai");

    /** Sample country names for nationality and location generation. */
    public static final List<String> COUNTRIES = List.of(
            "United States", "United Kingdom", "Germany", "France",
            "Japan", "Australia", "Canada", "Netherlands");

    /** Lorem ipsum vocabulary used for sentence and slug generation. */
    public static final List<String> LOREM_WORDS = List.of(
            "lorem", "ipsum", "dolor", "sit", "amet", "consectetur",
            "adipiscing", "elit", "sed", "do", "eiusmod", "tempor",
            "incididunt", "ut", "labore", "dolore", "magna", "aliqua");

    /** Sample status strings for {@code status}/{@code state}/{@code flag} parameters. */
    public static final List<String> STATUSES = List.of(
            "ACTIVE", "INACTIVE", "PENDING", "ENABLED", "DISABLED");

    /** HTTP method tokens for {@code method}/{@code httpMethod} parameters. */
    public static final List<String> HTTP_METHODS = List.of(
            "GET", "POST", "PUT", "DELETE");

    private DataPool() {}
}
