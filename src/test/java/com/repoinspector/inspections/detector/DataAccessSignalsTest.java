package com.repoinspector.inspections.detector;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataAccessSignalsTest {

    // ── EntityManager / Query executors ───────────────────────────────────────

    @Test
    void isEntityManagerQueryMethod_trueForExecutors() {
        assertTrue(DataAccessSignals.isEntityManagerQueryMethod("find"));
        assertTrue(DataAccessSignals.isEntityManagerQueryMethod("getReference"));
        assertTrue(DataAccessSignals.isEntityManagerQueryMethod("getResultList"));
        assertTrue(DataAccessSignals.isEntityManagerQueryMethod("getSingleResult"));
    }

    @Test
    void isEntityManagerQueryMethod_falseForBuildersAndNull() {
        // createQuery only builds; the terminal getResultList() is what hits the DB.
        assertFalse(DataAccessSignals.isEntityManagerQueryMethod("createQuery"));
        assertFalse(DataAccessSignals.isEntityManagerQueryMethod("persist"));
        assertFalse(DataAccessSignals.isEntityManagerQueryMethod(null));
    }

    // ── Hibernate Session reads ───────────────────────────────────────────────

    @Test
    void isHibernateSessionReadMethod_trueForReads() {
        assertTrue(DataAccessSignals.isHibernateSessionReadMethod("get"));
        assertTrue(DataAccessSignals.isHibernateSessionReadMethod("load"));
        assertTrue(DataAccessSignals.isHibernateSessionReadMethod("find"));
        assertTrue(DataAccessSignals.isHibernateSessionReadMethod("byId"));
    }

    @Test
    void isHibernateSessionReadMethod_falseAndNullSafe() {
        assertFalse(DataAccessSignals.isHibernateSessionReadMethod("save"));
        assertFalse(DataAccessSignals.isHibernateSessionReadMethod(null));
    }

    // ── JdbcTemplate queries ──────────────────────────────────────────────────

    @Test
    void isJdbcQueryMethod_trueForQueries() {
        assertTrue(DataAccessSignals.isJdbcQueryMethod("query"));
        assertTrue(DataAccessSignals.isJdbcQueryMethod("queryForObject"));
        assertTrue(DataAccessSignals.isJdbcQueryMethod("queryForList"));
    }

    @Test
    void isJdbcQueryMethod_falseAndNullSafe() {
        assertFalse(DataAccessSignals.isJdbcQueryMethod("update"));
        assertFalse(DataAccessSignals.isJdbcQueryMethod(null));
    }
}
