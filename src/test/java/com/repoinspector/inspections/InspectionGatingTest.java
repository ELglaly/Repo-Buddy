package com.repoinspector.inspections;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import com.repoinspector.inspections.scan.RepoBuddyInspectionScanner;
import com.repoinspector.inspections.scan.RepoBuddyInspectionScanner.Finding;
import com.repoinspector.settings.RepoBuddySettings;

import java.util.List;

/**
 * Verifies panel-only gating end to end: the registered (no-arg) inspection stays silent in the
 * editor daemon when panel-only mode is on, comes back when it is off, while the scanner — which
 * builds the inspections with {@code alwaysAnalyze = true} — always reports.
 */
public class InspectionGatingTest extends LightJavaCodeInsightFixtureTestCase {

    private static final String WRITE_DAO =
            "import jakarta.persistence.EntityManager;\n"
                    + "class UserDao {\n"
                    + "  private EntityManager em;\n"
                    + "  void add(Object u) { em.persist(u); }\n"
                    + "}\n";

    private boolean originalPanelOnly;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        originalPanelOnly = RepoBuddySettings.getInstance().isPanelOnlyMode();
        myFixture.addClass("package jakarta.persistence; public interface EntityManager { void persist(Object e); }");
        myFixture.addClass("package org.springframework.transaction.annotation; public @interface Transactional {}");
        myFixture.enableInspections(new MissingTransactionalInspection());
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            RepoBuddySettings.getInstance().setPanelOnlyMode(originalPanelOnly);
        } finally {
            super.tearDown();
        }
    }

    public void testPanelOnlyMode_suppressesEditorHighlight() {
        RepoBuddySettings.getInstance().setPanelOnlyMode(true);
        myFixture.configureByText("UserDao.java", WRITE_DAO);

        assertFalse("panel-only mode should suppress the inline @Transactional warning",
                hasTransactionalHighlight(myFixture.doHighlighting()));
    }

    public void testDualMode_showsEditorHighlight() {
        RepoBuddySettings.getInstance().setPanelOnlyMode(false);
        myFixture.configureByText("UserDao.java", WRITE_DAO);

        assertTrue("with panel-only off the inspection should warn inline",
                hasTransactionalHighlight(myFixture.doHighlighting()));
    }

    public void testScannerReportsEvenInPanelOnlyMode() {
        RepoBuddySettings.getInstance().setPanelOnlyMode(true);
        var file = myFixture.configureByText("UserDao.java", WRITE_DAO);

        List<Finding> findings = new RepoBuddyInspectionScanner().scanFile(getProject(), file);

        assertTrue("scanner must report regardless of panel-only mode",
                findings.stream().anyMatch(f -> f.inspection().equals("Missing @Transactional")));
    }

    private static boolean hasTransactionalHighlight(List<HighlightInfo> highlights) {
        return highlights.stream().anyMatch(h ->
                h.getDescription() != null && h.getDescription().contains("@Transactional"));
    }
}
