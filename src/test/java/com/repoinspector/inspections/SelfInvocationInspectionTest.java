package com.repoinspector.inspections;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;

import java.util.List;

/**
 * Fixture (integration) tests for {@link SelfInvocationInspection}.
 */
public class SelfInvocationInspectionTest extends LightJavaCodeInsightFixtureTestCase {

    // alwaysAnalyze = true bypasses panel-only gating so these tests exercise the analysis logic.
    private final SelfInvocationInspection inspection = new SelfInvocationInspection(true);

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.addClass("package org.springframework.transaction.annotation;"
                + " public @interface Transactional {}");
        myFixture.enableInspections(inspection);
    }

    private List<HighlightInfo> highlight(String decl) {
        myFixture.configureByText("Sample.java",
                "import org.springframework.transaction.annotation.Transactional;\n" + decl + "\n");
        return myFixture.doHighlighting();
    }

    private long warnings(List<HighlightInfo> infos) {
        return infos.stream()
                .filter(i -> i.getSeverity() == HighlightSeverity.WARNING)
                .filter(i -> i.getDescription() != null && i.getDescription().contains("Self-invocation"))
                .count();
    }

    public void testUnqualifiedSelfCall_flagged() {
        assertEquals(1, warnings(
                highlight("class Svc { @Transactional void save() {} void caller() { save(); } }")));
    }

    public void testThisQualifiedSelfCall_flagged() {
        assertEquals(1, warnings(
                highlight("class Svc { @Transactional void save() {} void caller() { this.save(); } }")));
    }

    public void testExternalCall_notFlagged() {
        assertEquals(0, warnings(
                highlight("class Svc { @Transactional void save() {} void caller(Svc other) { other.save(); } }")));
    }

    public void testNonTransactionalCallee_notFlagged() {
        assertEquals(0, warnings(
                highlight("class Svc { void save() {} void caller() { save(); } }")));
    }

    public void testClassLevelTransactionalOnly_notFlagged() {
        assertEquals(0, warnings(
                highlight("@Transactional class Svc { void save() {} void caller() { save(); } }")));
    }

    public void testStaticCallee_notFlagged() {
        assertEquals(0, warnings(
                highlight("class Svc { @Transactional static void save() {} void caller() { save(); } }")));
    }
}
