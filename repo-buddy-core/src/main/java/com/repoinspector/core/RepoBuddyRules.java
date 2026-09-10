package com.repoinspector.core;

import java.util.List;
import java.util.Locale;

public final class RepoBuddyRules {
    private RepoBuddyRules() {}

    public static final RepoBuddyRule UNSAFE_QUERY = rule("unsafe-query", "QUERY", "Unsafe @Query",
            "DATABASE_SECURITY", Severity.HIGH,
            "Reports missing named parameter bindings, SpEL interpolation surfaces, and risky query construction.",
            "Bind parameters explicitly and keep query structure static.");
    public static final RepoBuddyRule MISSING_PAGINATION = rule("missing-pagination", "PAGE", "Missing pagination",
            "DATABASE_PERFORMANCE", Severity.MEDIUM,
            "Reports repository methods that can return an unbounded collection.",
            "Return Page or Slice and accept Pageable, or deliberately bound the query.");
    public static final RepoBuddyRule MISSING_TRANSACTIONAL = rule("missing-transactional", "TX", "Missing @Transactional",
            "TRANSACTION", Severity.HIGH,
            "Reports database writes that execute without a clear transactional boundary.",
            "Place the write behind a proxied transactional service boundary.");
    public static final RepoBuddyRule N_PLUS_ONE = rule("n-plus-one", "NPLUS1", "N+1 query",
            "DATABASE_PERFORMANCE", Severity.HIGH,
            "Reports lazy association access or database query execution repeated inside iteration.",
            "Fetch or batch the required data before iteration using an appropriate query strategy.");
    public static final RepoBuddyRule SELF_INVOCATION = rule("transactional-self-invocation", "SELFTX",
            "@Transactional self-invocation", "TRANSACTION", Severity.HIGH,
            "Reports same-class calls that bypass Spring's transactional proxy.",
            "Move the operation behind another bean or invoke it through an appropriate proxy boundary.");

    private static final List<RepoBuddyRule> ALL = List.of(UNSAFE_QUERY, MISSING_PAGINATION,
            MISSING_TRANSACTIONAL, N_PLUS_ONE, SELF_INVOCATION);

    public static List<RepoBuddyRule> all() { return ALL; }

    public static RepoBuddyRule byId(String id) {
        if (id == null) return null;
        String normalized = id.toLowerCase(Locale.ROOT);
        return ALL.stream().filter(rule -> rule.id().equals(normalized)).findFirst().orElse(null);
    }

    public static RepoBuddyRule byName(String name) {
        return ALL.stream().filter(rule -> rule.name().equals(name)).findFirst().orElse(null);
    }

    private static RepoBuddyRule rule(String id, String prefix, String name, String category,
                                      Severity severity, String description, String recommendation) {
        return new RepoBuddyRule(id, prefix, name, category, severity, true, description,
                recommendation, List.of());
    }
}
