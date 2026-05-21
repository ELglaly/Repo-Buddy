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
        assertTrue(hit.message(), hit.message().contains("database write"));
        assertEquals("UserDao.java", hit.fileName());
        assertTrue("line should be 1-based positive, was " + hit.line(), hit.line() > 0);
    }

    public void testScanFileCleanFile_noFindings() {
        PsiFile file = myFixture.configureByText("Plain.java",
                "class Plain {\n"
                        + "  int add(int a, int b) { return a + b; }\n"
                        + "}\n");

        assertEmpty(scanner.scanFile(getProject(), file));
    }
}
