package com.repoinspector.agent.sql;

import com.repoinspector.agent.dto.SqlLogEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlLogStoreTest {

    @AfterEach
    void clearCaptureScope() {
        SqlLogStore.finishCapture();
    }

    @Test
    void ignoresSqlOutsideAnExplicitCaptureScope() {
        SqlLogStore.add("select ignored");

        SqlLogStore.Capture capture = SqlLogStore.finishCapture();

        assertTrue(capture.entries().isEmpty());
        assertFalse(capture.overflowed());
    }

    @Test
    void capsEntriesAndReportsStatementsDroppedAfterTheLimit() {
        SqlLogStore.beginCapture();
        for (int i = 0; i < SqlLogStore.MAX_STATEMENTS + 3; i++) {
            SqlLogStore.add("select " + i);
        }

        SqlLogStore.Capture capture = SqlLogStore.finishCapture();

        assertEquals(SqlLogStore.MAX_STATEMENTS, capture.entries().size());
        assertEquals(3, capture.droppedCount());
        assertTrue(capture.overflowed());
    }

    @Test
    void truncatesOversizedSqlAndRemovesThreadLocalStateAtFinish() {
        SqlLogStore.beginCapture();
        SqlLogStore.add("x".repeat(SqlLogStore.MAX_SQL_CHARS + 1));

        SqlLogStore.Capture capture = SqlLogStore.finishCapture();
        SqlLogEntry entry = capture.entries().get(0);

        assertEquals(SqlLogStore.MAX_SQL_CHARS, entry.sql().length());
        assertTrue(entry.truncated());
        assertTrue(SqlLogStore.finishCapture().entries().isEmpty());
    }
}
