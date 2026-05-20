package com.repoinspector.inspections.detector;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pure (PSI-free) heuristics for deciding whether a Spring Data repository
 * method returns an unbounded result set that should probably be paginated.
 */
public final class PaginationSignatureAnalyzer {

    private PaginationSignatureAnalyzer() {}

    /** JDK collection / stream types that carry no built-in result-size bound. */
    private static final Set<String> UNBOUNDED_COLLECTION_FQNS = Set.of(
            "java.util.List",
            "java.util.Set",
            "java.util.Collection",
            "java.lang.Iterable",
            "java.util.stream.Stream"
    );

    /** A {@code All} camel-case segment ({@code findAll}, {@code findAllByX}) — not {@code Allowed}/{@code Small}. */
    private static final Pattern FIND_ALL = Pattern.compile("All([A-Z]|$)");

    /** Spring Data {@code First}/{@code Top} limiting keyword ({@code findFirstBy}, {@code findTop10By}). */
    private static final Pattern LIMITED_NAME = Pattern.compile("(First|Top)\\d*(?=[A-Z]|$)");

    public static boolean isUnboundedCollection(String returnTypeFqn) {
        return returnTypeFqn != null && UNBOUNDED_COLLECTION_FQNS.contains(returnTypeFqn);
    }

    /** True if the method name reads like a "fetch everything" query. */
    public static boolean looksLikeFindAll(String methodName) {
        return methodName != null && FIND_ALL.matcher(methodName).find();
    }

    /** True if the method name already bounds its result with a {@code First}/{@code Top} keyword. */
    public static boolean isLimitedByName(String methodName) {
        return methodName != null && LIMITED_NAME.matcher(methodName).find();
    }
}
