package com.repoinspector.inspections.detector;

import java.util.Set;

/**
 * Pure (PSI-free) predicates identifying database write operations that require an
 * active transaction. Covers three families:
 *
 * <ul>
 *   <li><b>JPA</b> &mdash; {@code EntityManager}/{@code Query} mutators
 *       ({@code persist}/{@code merge}/{@code remove}/{@code flush}/{@code executeUpdate}).</li>
 *   <li><b>Hibernate native</b> &mdash; {@code org.hibernate.Session} mutators
 *       ({@code save}/{@code update}/{@code saveOrUpdate}/{@code delete}/&hellip;).</li>
 *   <li><b>JDBC</b> &mdash; {@code JdbcTemplate} mutators
 *       ({@code update}/{@code batchUpdate}/{@code execute}).</li>
 * </ul>
 */
public final class TransactionWriteSignals {

    private TransactionWriteSignals() {}

    /** {@code EntityManager}/{@code Query} methods that mutate and need a transaction. */
    private static final Set<String> JPA_WRITE_METHODS = Set.of(
            "persist", "merge", "remove", "flush", "executeUpdate"
    );

    /** {@code org.hibernate.Session}/{@code StatelessSession} mutators. */
    private static final Set<String> HIBERNATE_WRITE_METHODS = Set.of(
            "save", "saveOrUpdate", "update", "delete", "persist", "merge", "remove",
            "flush", "replicate", "insert", "upsert"
    );

    /** {@code JdbcTemplate}/{@code NamedParameterJdbcTemplate} mutators. */
    private static final Set<String> JDBC_WRITE_METHODS = Set.of(
            "update", "batchUpdate", "execute"
    );

    private static final String[] PERSISTENCE_PACKAGES = {
            "jakarta.persistence.", "javax.persistence."
    };

    public static final String HIBERNATE_SESSION = "org.hibernate.Session";
    public static final String HIBERNATE_STATELESS_SESSION = "org.hibernate.StatelessSession";

    public static final String JDBC_TEMPLATE = "org.springframework.jdbc.core.JdbcTemplate";
    public static final String NAMED_JDBC_TEMPLATE =
            "org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate";

    public static boolean isJpaWriteMethod(String methodName) {
        return methodName != null && JPA_WRITE_METHODS.contains(methodName);
    }

    public static boolean isHibernateWriteMethod(String methodName) {
        return methodName != null && HIBERNATE_WRITE_METHODS.contains(methodName);
    }

    public static boolean isJdbcWriteMethod(String methodName) {
        return methodName != null && JDBC_WRITE_METHODS.contains(methodName);
    }

    /** True if the FQN belongs to the standard JPA API ({@code EntityManager}, {@code Query}, …). */
    public static boolean isJpaPersistenceType(String fqn) {
        if (fqn == null) return false;
        for (String pkg : PERSISTENCE_PACKAGES) {
            if (fqn.startsWith(pkg)) return true;
        }
        return false;
    }

    /** True for {@code org.hibernate.Session} / {@code StatelessSession}. */
    public static boolean isHibernateSessionType(String fqn) {
        return HIBERNATE_SESSION.equals(fqn) || HIBERNATE_STATELESS_SESSION.equals(fqn);
    }

    /** True for Spring's {@code JdbcTemplate} / {@code NamedParameterJdbcTemplate}. */
    public static boolean isJdbcTemplateType(String fqn) {
        return JDBC_TEMPLATE.equals(fqn) || NAMED_JDBC_TEMPLATE.equals(fqn);
    }
}
