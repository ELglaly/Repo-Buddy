package com.repoinspector.agent.sql;

import org.hibernate.resource.jdbc.spi.StatementInspector;

/**
 * Hibernate {@link StatementInspector} that forwards every SQL statement to
 * {@link SqlLogStore} for per-thread capture.
 *
 * <p>Registered as a class name in Hibernate properties so that it is only
 * loaded when Hibernate is actually on the classpath:
 * <pre>
 *   hibernate.session_factory.statement_inspector =
 *       com.repoinspector.agent.sql.SqlCapturingInterceptor
 * </pre>
 *
 * <p>Hibernate instantiates this class via its public no-arg constructor once
 * per session factory.  All requests share the single instance but write to
 * their own thread-local store in {@link SqlLogStore}.
 */
public final class SqlCapturingInterceptor implements StatementInspector {

    /** Required no-arg constructor — Hibernate instantiates this via reflection. */
    public SqlCapturingInterceptor() {}

    @Override
    public String inspect(String sql) {
        SqlLogStore.add(sql);
        return sql; // never modify the SQL
    }
}
