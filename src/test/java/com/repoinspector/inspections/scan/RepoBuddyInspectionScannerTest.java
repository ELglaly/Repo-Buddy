package com.repoinspector.inspections.scan;

import com.intellij.psi.PsiFile;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import com.repoinspector.inspections.scan.RepoBuddyInspectionScanner.Finding;

import java.util.List;

/**
 * Integration tests for {@link RepoBuddyInspectionScanner}: confirms the scanner actually
 * runs the real inspections through {@code InspectionEngine} and flattens their findings.
 */
public class RepoBuddyInspectionScannerTest extends LightJavaCodeInsightFixtureTestCase {

    private final RepoBuddyInspectionScanner scanner = new RepoBuddyInspectionScanner();

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.addClass("package jakarta.persistence; public interface EntityManager { void persist(Object e); }");
        myFixture.addClass("package org.springframework.transaction.annotation;"
                + " public @interface Transactional {}");
    }

    public void testScanFileFindsMissingTransactional() {
        PsiFile file = myFixture.configureByText("UserDao.java",
                "import jakarta.persistence.EntityManager;\n"
                        + "class UserDao {\n"
                        + "  private EntityManager em;\n"
                        + "  void add(Object u) { em.persist(u); }\n"
                        + "}\n");

        List<Finding> findings = scanner.scanFile(getProject(), file);

        Finding hit = findings.stream()
                .filter(f -> f.inspection().equals("Missing @Transactional"))
                .findFirst().orElse(null);
        assertNotNull("expected a Missing @Transactional finding", hit);
        assertEquals("missing-transactional", hit.ruleId());
        assertTrue(hit.message(), hit.message().contains("database write"));
        assertEquals("UserDao.java", hit.fileName());
        assertTrue("line should be 1-based positive, was " + hit.line(), hit.line() > 0);
        assertTrue("column should be 1-based positive, was " + hit.column(), hit.column() > 0);
        assertFalse("stable anchor should be present", hit.stableAnchor().isBlank());
    }

    public void testScanFileCleanFile_noFindings() {
        PsiFile file = myFixture.configureByText("Plain.java",
                "class Plain {\n"
                        + "  int add(int a, int b) { return a + b; }\n"
                        + "}\n");

        assertEmpty(scanner.scanFile(getProject(), file));
    }

    public void testStableAnchorDoesNotChangeWhenLinesAreInsertedBeforeIssue() {
        PsiFile before = myFixture.configureByText("UserDao.java",
                "import jakarta.persistence.EntityManager;\nclass UserDao {\n  EntityManager em;\n  void add(Object u) { em.persist(u); }\n}\n");
        Finding original = scanner.scanFile(getProject(), before).stream()
                .filter(f -> f.ruleId().equals("missing-transactional")).findFirst().orElseThrow();

        PsiFile after = myFixture.configureByText("UserDao.java",
                "\n\nimport jakarta.persistence.EntityManager;\nclass UserDao {\n  EntityManager em;\n  void add(Object u) { em.persist(u); }\n}\n");
        Finding moved = scanner.scanFile(getProject(), after).stream()
                .filter(f -> f.ruleId().equals("missing-transactional")).findFirst().orElseThrow();

        assertEquals(original.stableAnchor(), moved.stableAnchor());
        assertTrue(moved.line() > original.line());
    }
}
