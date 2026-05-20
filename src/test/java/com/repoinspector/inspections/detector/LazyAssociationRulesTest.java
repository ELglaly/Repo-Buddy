package com.repoinspector.inspections.detector;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LazyAssociationRulesTest {

    // ── isLazyAssociation() — collection associations default LAZY ─────────────

    @Test
    void collectionAssociations_lazyByDefault() {
        assertTrue(LazyAssociationRules.isLazyAssociation("OneToMany", null));
        assertTrue(LazyAssociationRules.isLazyAssociation("ManyToMany", null));
    }

    @Test
    void collectionAssociations_notLazyWhenEager() {
        assertFalse(LazyAssociationRules.isLazyAssociation("OneToMany", "EAGER"));
        assertFalse(LazyAssociationRules.isLazyAssociation("ManyToMany", "EAGER"));
    }

    @Test
    void collectionAssociations_lazyWhenExplicitLazy() {
        assertTrue(LazyAssociationRules.isLazyAssociation("ManyToMany", "LAZY"));
    }

    // ── isLazyAssociation() — singular associations default EAGER ──────────────

    @Test
    void singularAssociations_notLazyByDefault() {
        assertFalse(LazyAssociationRules.isLazyAssociation("ManyToOne", null));
        assertFalse(LazyAssociationRules.isLazyAssociation("OneToOne", null));
    }

    @Test
    void singularAssociations_lazyWhenExplicitLazy() {
        assertTrue(LazyAssociationRules.isLazyAssociation("ManyToOne", "LAZY"));
        assertTrue(LazyAssociationRules.isLazyAssociation("OneToOne", "LAZY"));
    }

    @Test
    void singularAssociations_notLazyWhenEager() {
        assertFalse(LazyAssociationRules.isLazyAssociation("ManyToOne", "EAGER"));
    }

    @Test
    void nonAssociation_isNeverLazy() {
        assertFalse(LazyAssociationRules.isLazyAssociation("Embedded", null));
        assertFalse(LazyAssociationRules.isLazyAssociation(null, "LAZY"));
    }

    // ── propertyNameFromGetter() ──────────────────────────────────────────────

    @Test
    void propertyName_fromGetPrefix() {
        assertEquals("addresses", LazyAssociationRules.propertyNameFromGetter("getAddresses"));
        assertEquals("company", LazyAssociationRules.propertyNameFromGetter("getCompany"));
    }

    @Test
    void propertyName_fromIsPrefix() {
        assertEquals("active", LazyAssociationRules.propertyNameFromGetter("isActive"));
    }

    @Test
    void propertyName_javaBeansAcronymStaysUpper() {
        assertEquals("URL", LazyAssociationRules.propertyNameFromGetter("getURL"));
    }

    @Test
    void propertyName_nullForNonGetter() {
        assertNull(LazyAssociationRules.propertyNameFromGetter("compute"));
        assertNull(LazyAssociationRules.propertyNameFromGetter("get"));
        assertNull(LazyAssociationRules.propertyNameFromGetter("is"));
        assertNull(LazyAssociationRules.propertyNameFromGetter(null));
    }
}
