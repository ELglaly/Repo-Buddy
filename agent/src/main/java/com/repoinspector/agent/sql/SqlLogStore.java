package com.repoinspector.agent.sql;

import com.repoinspector.agent.dto.SqlLogEntry;

import java.util.ArrayList;
import java.util.List;

/**
 * Thread-local store for SQL statements captured by {@link SqlCapturingInterceptor}.
 *
 * <p>Usage pattern per request:
 * <ol>
 *   <li>Call {@link #beginCapture()} immediately before invoking the repository method.</li>
 *   <li>Hibernate calls {@link #add(String)} for each SQL statement.</li>
 *   <li>Call {@link #finishCapture()} in {@code finally} to collect and remove the log.</li>
 * </ol>
 * SQL outside that explicit scope is intentionally ignored. This avoids retaining target
 * application SQL on long-lived executor threads.
 */
public final class SqlLogStore {

    public static final int MAX_STATEMENTS = 1_000;
    public static final int MAX_SQL_CHARS = 16 * 1024;

    private static final ThreadLocal<CaptureState> STORE = new ThreadLocal<>();

    /** Immutable result of one capture scope. */
    public record Capture(List<SqlLogEntry> entries, int droppedCount) {
        public boolean overflowed() { return droppedCount > 0; }
    }

    private static final class CaptureState {
        private final List<SqlLogEntry> entries = new ArrayList<>();
        private int droppedCount;
    }

    private SqlLogStore() {}

    /** Starts an explicit bounded capture scope on the current thread. */
    public static void beginCapture() {
        STORE.set(new CaptureState());
    }

    /** Records a SQL statement for the current thread. */
    public static void add(String sql) {
        CaptureState state = STORE.get();
        if (state == null) return;
        if (state.entries.size() >= MAX_STATEMENTS) {
            state.droppedCount++;
            return;
        }
        String value = sql == null ? "" : sql;
        boolean truncated = value.length() > MAX_SQL_CHARS;
        if (truncated) value = value.substring(0, MAX_SQL_CHARS);
        state.entries.add(new SqlLogEntry(value, System.currentTimeMillis(), truncated));
    }

    /** Returns an immutable snapshot of captured statements for the current thread. */
    public static Capture finishCapture() {
        CaptureState state = STORE.get();
        try {
            return state == null ? new Capture(List.of(), 0)
                    : new Capture(List.copyOf(state.entries), state.droppedCount);
        } finally {
            STORE.remove();
        }
    }
}
