package com.repoinspector.inspections.detector;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TransactionWriteSignalsTest {

    // ── isJpaWriteMethod() ────────────────────────────────────────────────────

    @Test
    void isJpaWriteMethod_trueForMutatingMethods() {
        assertTrue(TransactionWriteSignals.isJpaWriteMethod("persist"));
        assertTrue(TransactionWriteSignals.isJpaWriteMethod("merge"));
        assertTrue(TransactionWriteSignals.isJpaWriteMethod("remove"));
        assertTrue(TransactionWriteSignals.isJpaWriteMethod("flush"));
        assertTrue(TransactionWriteSignals.isJpaWriteMethod("executeUpdate"));
    }

    @Test
    void isJpaWriteMethod_falseForReadAndOther() {
        assertFalse(TransactionWriteSignals.isJpaWriteMethod("find"));
        assertFalse(TransactionWriteSignals.isJpaWriteMethod("createQuery"));
        assertFalse(TransactionWriteSignals.isJpaWriteMethod("getResultList"));
        assertFalse(TransactionWriteSignals.isJpaWriteMethod("save"));
    }

    @Test
    void isJpaWriteMethod_nullSafe() {
        assertFalse(TransactionWriteSignals.isJpaWriteMethod(null));
    }

    // ── isJpaPersistenceType() ────────────────────────────────────────────────

    @Test
    void isJpaPersistenceType_trueForJakartaAndJavax() {
        assertTrue(TransactionWriteSignals.isJpaPersistenceType("jakarta.persistence.EntityManager"));
        assertTrue(TransactionWriteSignals.isJpaPersistenceType("jakarta.persistence.Query"));
        assertTrue(TransactionWriteSignals.isJpaPersistenceType("javax.persistence.EntityManager"));
        assertTrue(TransactionWriteSignals.isJpaPersistenceType("javax.persistence.TypedQuery"));
    }

    @Test
    void isJpaPersistenceType_falseForOtherTypes() {
        assertFalse(TransactionWriteSignals.isJpaPersistenceType("com.example.MyEntityManager"));
        assertFalse(TransactionWriteSignals.isJpaPersistenceType("java.util.List"));
    }

    @Test
    void isJpaPersistenceType_nullSafe() {
        assertFalse(TransactionWriteSignals.isJpaPersistenceType(null));
    }

    // ── Hibernate-native writes ───────────────────────────────────────────────

    @Test
    void isHibernateWriteMethod_trueForMutators() {
        assertTrue(TransactionWriteSignals.isHibernateWriteMethod("save"));
        assertTrue(TransactionWriteSignals.isHibernateWriteMethod("saveOrUpdate"));
        assertTrue(TransactionWriteSignals.isHibernateWriteMethod("update"));
        assertTrue(TransactionWriteSignals.isHibernateWriteMethod("delete"));
    }

    @Test
    void isHibernateWriteMethod_falseAndNullSafe() {
        assertFalse(TransactionWriteSignals.isHibernateWriteMethod("get"));
        assertFalse(TransactionWriteSignals.isHibernateWriteMethod("load"));
        assertFalse(TransactionWriteSignals.isHibernateWriteMethod(null));
    }

    @Test
    void isHibernateSessionType_matchesSessionTypes() {
        assertTrue(TransactionWriteSignals.isHibernateSessionType("org.hibernate.Session"));
        assertTrue(TransactionWriteSignals.isHibernateSessionType("org.hibernate.StatelessSession"));
        assertFalse(TransactionWriteSignals.isHibernateSessionType("jakarta.persistence.EntityManager"));
        assertFalse(TransactionWriteSignals.isHibernateSessionType(null));
    }

    // ── JDBC writes ───────────────────────────────────────────────────────────

    @Test
    void isJdbcWriteMethod_trueForMutators() {
        assertTrue(TransactionWriteSignals.isJdbcWriteMethod("update"));
        assertTrue(TransactionWriteSignals.isJdbcWriteMethod("batchUpdate"));
        assertTrue(TransactionWriteSignals.isJdbcWriteMethod("execute"));
    }

    @Test
    void isJdbcWriteMethod_falseAndNullSafe() {
        assertFalse(TransactionWriteSignals.isJdbcWriteMethod("query"));
        assertFalse(TransactionWriteSignals.isJdbcWriteMethod("queryForObject"));
        assertFalse(TransactionWriteSignals.isJdbcWriteMethod(null));
    }

    @Test
    void isJdbcTemplateType_matchesTemplateTypes() {
        assertTrue(TransactionWriteSignals.isJdbcTemplateType("org.springframework.jdbc.core.JdbcTemplate"));
        assertTrue(TransactionWriteSignals.isJdbcTemplateType(
                "org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate"));
        assertFalse(TransactionWriteSignals.isJdbcTemplateType("java.util.List"));
        assertFalse(TransactionWriteSignals.isJdbcTemplateType(null));
    }
}
