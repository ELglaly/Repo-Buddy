package com.repoinspector.inspections.detector;

import java.util.Set;

/**
 * Pure (PSI-free) predicates identifying database <em>read</em>/query operations
 * that perform a round-trip to the database. Used by the N+1 detector to flag a
 * query issued once per loop iteration.
 *
 * <p>Repository (Spring Data) calls are classified separately in
 * {@link DataAccessCalls} because that requires PSI type resolution. This class
 * covers the API surfaces whose owning type can be matched by fully-qualified
 * name: {@code EntityManager}/{@code Query}, {@code org.hibernate.Session}, and
 * Spring's {@code JdbcTemplate}.
 */
public final class DataAccessSignals {

    private DataAccessSignals() {}

    /**
     * {@code EntityManager}/{@code Query} methods that execute a query. Only the
     * terminal executors are listed ({@code getResultList} etc.) plus {@code find}/
     * {@code getReference}; builder methods such as {@code createQuery} are excluded
     * so a single {@code createQuery(..).getResultList()} statement is flagged once.
     */
    private static final Set<String> ENTITY_MANAGER_QUERY_METHODS = Set.of(
            "find", "getReference",
            "getResultList", "getResultStream", "getSingleResult", "getSingleResultOrNull"
    );

    /** {@code org.hibernate.Session} per-row / query read methods. */
    private static final Set<String> SESSION_READ_METHODS = Set.of(
            "get", "load", "find", "byId", "byMultipleIds",
            "bySimpleNaturalId", "byNaturalId"
    );

    /** {@code JdbcTemplate}/{@code NamedParameterJdbcTemplate} query methods. */
    private static final Set<String> JDBC_QUERY_METHODS = Set.of(
            "query", "queryForObject", "queryForList", "queryForMap",
            "queryForRowSet", "queryForStream"
    );

    public static boolean isEntityManagerQueryMethod(String methodName) {
        return methodName != null && ENTITY_MANAGER_QUERY_METHODS.contains(methodName);
    }

    public static boolean isHibernateSessionReadMethod(String methodName) {
        return methodName != null && SESSION_READ_METHODS.contains(methodName);
    }

    public static boolean isJdbcQueryMethod(String methodName) {
        return methodName != null && JDBC_QUERY_METHODS.contains(methodName);
    }
}
