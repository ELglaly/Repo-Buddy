package com.repoinspector.agent.sql;

import com.repoinspector.agent.dto.SqlLogEntry;

import java.util.ArrayList;
import java.util.List;

/**
 * Thread-local store for SQL statements captured by {@link SqlCapturingInterceptor}.
 *
 * <p>Usage pattern per request:
 * <ol>
 *   <li>Call {@link #clear()} before invoking the repository method.</li>
 *   <li>Hibernate calls {@link #add(String)} for each SQL statement.</li>
 *   <li>Call {@link #snapshot()} after the method returns to collect the log.</li>
 * </ol>
 */
public final class SqlLogStore {

    private static final ThreadLocal<List<SqlLogEntry>> STORE =
            ThreadLocal.withInitial(ArrayList::new);

    private SqlLogStore() {}

    /** Clears all captured statements for the current thread. */
    public static void clear() {
        STORE.get().clear();
    }

    /** Records a SQL statement for the current thread. */
    public static void add(String sql) {
        STORE.get().add(new SqlLogEntry(sql, System.currentTimeMillis()));
    }

    /** Returns an immutable snapshot of captured statements for the current thread. */
    public static List<SqlLogEntry> snapshot() {
        return List.copyOf(STORE.get());
    }
}
