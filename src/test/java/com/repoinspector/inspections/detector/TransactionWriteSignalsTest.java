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
}
