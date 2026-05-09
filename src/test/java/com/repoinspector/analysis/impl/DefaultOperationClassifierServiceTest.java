package com.repoinspector.analysis.impl;

import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiType;
import com.repoinspector.constants.SpringAnnotations;
import com.repoinspector.model.OperationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DefaultOperationClassifierServiceTest {

    private DefaultOperationClassifierService service;

    @Mock PsiMethod method;
    @Mock PsiClass containingClass;

    @BeforeEach
    void setUp() {
        service = new DefaultOperationClassifierService();
    }

    // ── classify() ───────────────────────────────────────────────────────────

    @Test
    void classify_withModifyingAnnotation_returnsWrite() {
        when(method.hasAnnotation(SpringAnnotations.MODIFYING)).thenReturn(true);
        assertEquals(OperationType.WRITE, service.classify(method));
    }

    @Test
    void classify_savePrefix_returnsWrite() {
        when(method.hasAnnotation(SpringAnnotations.MODIFYING)).thenReturn(false);
        when(method.getName()).thenReturn("saveUser");
        assertEquals(OperationType.WRITE, service.classify(method));
    }

    @Test
    void classify_deletePrefix_returnsWrite() {
        when(method.hasAnnotation(SpringAnnotations.MODIFYING)).thenReturn(false);
        when(method.getName()).thenReturn("deleteById");
        assertEquals(OperationType.WRITE, service.classify(method));
    }

    @Test
    void classify_findPrefix_returnsRead() {
        when(method.hasAnnotation(SpringAnnotations.MODIFYING)).thenReturn(false);
        when(method.getName()).thenReturn("findById");
        assertEquals(OperationType.READ, service.classify(method));
    }

    @Test
    void classify_getPrefix_returnsRead() {
        when(method.hasAnnotation(SpringAnnotations.MODIFYING)).thenReturn(false);
        when(method.getName()).thenReturn("getAll");
        assertEquals(OperationType.READ, service.classify(method));
    }

    @Test
    void classify_unknownPrefix_returnsUnknown() {
        when(method.hasAnnotation(SpringAnnotations.MODIFYING)).thenReturn(false);
        when(method.getName()).thenReturn("customOp");
        assertEquals(OperationType.UNKNOWN, service.classify(method));
    }

    @Test
    void classify_modifyingTakesPrecedenceOverReadPrefix() {
        // @Modifying triggers WRITE before getName() is ever called — no name stub needed.
        when(method.hasAnnotation(SpringAnnotations.MODIFYING)).thenReturn(true);
        assertEquals(OperationType.WRITE, service.classify(method));
    }

    // ── isTransactional() ────────────────────────────────────────────────────

    @Test
    void isTransactional_methodHasAnnotation_returnsTrue() {
        when(method.hasAnnotation(SpringAnnotations.TRANSACTIONAL)).thenReturn(true);
        assertTrue(service.isTransactional(method));
    }

    @Test
    void isTransactional_classHasAnnotation_returnsTrue() {
        when(method.hasAnnotation(SpringAnnotations.TRANSACTIONAL)).thenReturn(false);
        when(method.getContainingClass()).thenReturn(containingClass);
        when(containingClass.hasAnnotation(SpringAnnotations.TRANSACTIONAL)).thenReturn(true);
        assertTrue(service.isTransactional(method));
    }

    @Test
    void isTransactional_neitherHasAnnotation_returnsFalse() {
        when(method.hasAnnotation(SpringAnnotations.TRANSACTIONAL)).thenReturn(false);
        when(method.getContainingClass()).thenReturn(containingClass);
        when(containingClass.hasAnnotation(SpringAnnotations.TRANSACTIONAL)).thenReturn(false);
        assertFalse(service.isTransactional(method));
    }

    @Test
    void isTransactional_noContainingClass_returnsFalse() {
        when(method.hasAnnotation(SpringAnnotations.TRANSACTIONAL)).thenReturn(false);
        when(method.getContainingClass()).thenReturn(null);
        assertFalse(service.isTransactional(method));
    }

    // ── extractEntityName() ──────────────────────────────────────────────────

    @Test
    void extractEntityName_nullClass_returnsEmpty() {
        assertEquals("", service.extractEntityName(null));
    }

    @Test
    void extractEntityName_noSupertypes_returnsEmpty() {
        PsiClass repoClass = mock(PsiClass.class);
        when(repoClass.getExtendsListTypes()).thenReturn(new PsiClassType[0]);
        when(repoClass.getImplementsListTypes()).thenReturn(new PsiClassType[0]);
        assertEquals("", service.extractEntityName(repoClass));
    }

    @Test
    void extractEntityName_extendsJpaRepository_returnsEntityName() {
        PsiClass repoClass = mock(PsiClass.class);
        PsiClassType jpaType = mock(PsiClassType.class);
        PsiClassType rawType = mock(PsiClassType.class);
        PsiType entityType = mock(PsiType.class);

        when(repoClass.getExtendsListTypes()).thenReturn(new PsiClassType[]{jpaType});
        when(repoClass.getImplementsListTypes()).thenReturn(new PsiClassType[0]);
        when(jpaType.rawType()).thenReturn(rawType);
        when(rawType.getCanonicalText()).thenReturn(SpringAnnotations.JPA_REPOSITORY);
        when(jpaType.getParameters()).thenReturn(new PsiType[]{entityType});
        when(entityType.getPresentableText()).thenReturn("User");

        assertEquals("User", service.extractEntityName(repoClass));
    }

    @Test
    void extractEntityName_nonSpringDataSupertype_returnsEmpty() {
        PsiClass repoClass = mock(PsiClass.class);
        PsiClassType otherType = mock(PsiClassType.class);
        PsiClassType rawType = mock(PsiClassType.class);

        when(repoClass.getExtendsListTypes()).thenReturn(new PsiClassType[]{otherType});
        when(repoClass.getImplementsListTypes()).thenReturn(new PsiClassType[0]);
        when(otherType.rawType()).thenReturn(rawType);
        when(rawType.getCanonicalText()).thenReturn("com.example.SomeOtherBase");

        assertEquals("", service.extractEntityName(repoClass));
    }
}
