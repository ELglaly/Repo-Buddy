package com.repoinspector.inspections;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;

import java.util.List;

/**
 * Fixture (integration) tests for {@link UnsafeQueryInspection}. Minimal Spring Data
 * annotation stubs are injected so the inspection resolves the real FQNs.
 */
public class UnsafeQueryInspectionTest extends LightJavaCodeInsightFixtureTestCase {

    // alwaysAnalyze = true bypasses panel-only gating so these tests exercise the analysis logic.
    private final UnsafeQueryInspection inspection = new UnsafeQueryInspection(true);

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.addClass("package org.springframework.data.jpa.repository;"
                + " public @interface Query { String value() default \"\"; boolean nativeQuery() default false; }");
        myFixture.addClass("package org.springframework.data.repository.query;"
                + " public @interface Param { String value(); }");
        myFixture.addClass("package org.springframework.data.domain; public interface Pageable {}");
        myFixture.enableInspections(inspection);
    }

    private List<HighlightInfo> highlight(String body) {
        myFixture.configureByText("UserRepository.java",
                "import org.springframework.data.jpa.repository.Query;\n"
                        + "import org.springframework.data.repository.query.Param;\n"
                        + "import org.springframework.data.domain.Pageable;\n"
                        + "interface UserRepository {\n" + body + "\n}\n");
        return myFixture.doHighlighting();
    }

    private long warnings(List<HighlightInfo> infos, String needle) {
        return infos.stream()
                .filter(i -> i.getSeverity() == HighlightSeverity.WARNING)
                .filter(i -> i.getDescription() != null && i.getDescription().contains(needle))
                .count();
    }

    // ── missing @Param ────────────────────────────────────────────────────────

    public void testMissingParam_isFlagged() {
        List<HighlightInfo> infos = highlight(
                "  @Query(\"SELECT u FROM User u WHERE u.name = :name\")\n"
                        + "  Object byName(String email);");
        assertEquals(1, warnings(infos, "Named parameter ':name'"));
    }

    public void testParamPresent_notFlagged() {
        List<HighlightInfo> infos = highlight(
                "  @Query(\"SELECT u FROM User u WHERE u.name = :name\")\n"
                        + "  Object byName(@Param(\"name\") String name);");
        assertEquals(0, warnings(infos, "Named parameter"));
    }

    public void testMatchingParameterName_notFlagged() {
        List<HighlightInfo> infos = highlight(
                "  @Query(\"SELECT u FROM User u WHERE u.name = :name\")\n"
                        + "  Object byName(String name);");
        assertEquals(0, warnings(infos, "Named parameter"));
    }

    public void testPageableParamIsIgnoredAsTarget() {
        // 'name' is unmatched; the only non-@Param parameter is a Pageable, which is
        // a structural param — still flagged, but quick-fix target is suppressed.
        List<HighlightInfo> infos = highlight(
                "  @Query(\"SELECT u FROM User u WHERE u.name = :name\")\n"
                        + "  Object byName(Pageable page);");
        assertEquals(1, warnings(infos, "Named parameter ':name'"));
    }

    // ── SpEL ──────────────────────────────────────────────────────────────────

    public void testSpel_isFlagged() {
        List<HighlightInfo> infos = highlight(
                "  @Query(\"SELECT u FROM #{#entityName} u\")\n"
                        + "  Object all();");
        assertEquals(1, warnings(infos, "SpEL expression"));
    }

    public void testPlainQuery_noWarnings() {
        List<HighlightInfo> infos = highlight(
                "  @Query(\"SELECT u FROM User u\")\n"
                        + "  Object all();");
        assertEquals(0, warnings(infos, "SpEL expression"));
        assertEquals(0, warnings(infos, "Named parameter"));
    }

    // ── string concatenation ──────────────────────────────────────────────────

    public void testConcatenationOfLiterals_isFlaggedByDefault() {
        List<HighlightInfo> infos = highlight(
                "  @Query(\"SELECT u FROM User u \" + \"WHERE u.active = true\")\n"
                        + "  Object findActive();");
        assertEquals(1, warnings(infos, "concatenation"));
    }

    public void testConcatenationWithConstantRef_isFlaggedByDefault() {
        List<HighlightInfo> infos = highlight(
                "  String BASE_QUERY = \"SELECT u FROM AppUser u\";\n"
                        + "  @Query(BASE_QUERY + \" WHERE u.age > 18\")\n"
                        + "  Object findAdults();");
        assertEquals(1, warnings(infos, "concatenation"));
    }

    public void testSingleLiteral_notFlaggedForConcatenation() {
        List<HighlightInfo> infos = highlight(
                "  @Query(\"SELECT u FROM User u\")\n"
                        + "  Object all();");
        assertEquals(0, warnings(infos, "concatenation"));
    }

    public void testConcatenationSuppressedWhenAllowed() {
        inspection.allowConcatenationForConstants = true;
        List<HighlightInfo> infos = highlight(
                "  @Query(\"SELECT u FROM User u \" + \"WHERE u.active = true\")\n"
                        + "  Object findActive();");
        assertEquals(0, warnings(infos, "concatenation"));
    }

    // ── quick-fix ───────────────────────────────────────────────────────────--

    public void testAddParamQuickFix() {
        myFixture.configureByText("UserRepository.java",
                "import org.springframework.data.jpa.repository.Query;\n"
                        + "import org.springframework.data.repository.query.Param;\n"
                        + "interface UserRepository {\n"
                        + "  @Query(\"SELECT u FROM User u WHERE u.name = :na<caret>me\")\n"
                        + "  Object byName(String email);\n"
                        + "}\n");
        IntentionAction fix = myFixture.findSingleIntention("Add @Param(\"name\")");
        assertNotNull(fix);
        myFixture.launchAction(fix);
        myFixture.checkResult(
                "import org.springframework.data.jpa.repository.Query;\n"
                        + "import org.springframework.data.repository.query.Param;\n"
                        + "interface UserRepository {\n"
                        + "  @Query(\"SELECT u FROM User u WHERE u.name = :name\")\n"
                        + "  Object byName(@Param(\"name\") String email);\n"
                        + "}\n");
    }
}
