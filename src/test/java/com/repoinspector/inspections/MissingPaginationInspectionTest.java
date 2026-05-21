package com.repoinspector.inspections;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;

import java.util.List;

/**
 * Fixture (integration) tests for {@link MissingPaginationInspection}. Minimal Spring
 * Data stubs are injected so the repository hierarchy and domain types resolve.
 */
public class MissingPaginationInspectionTest extends LightJavaCodeInsightFixtureTestCase {

    // alwaysAnalyze = true bypasses panel-only gating so these tests exercise the analysis logic.
    private final MissingPaginationInspection inspection = new MissingPaginationInspection(true);

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        // The light test JDK does not resolve java.util collections; provide minimal stubs.
        myFixture.addClass("package java.util; public interface Collection<E> {}");
        myFixture.addClass("package java.util; public interface List<E> extends Collection<E> {}");
        myFixture.addClass("package java.util; public interface Set<E> extends Collection<E> {}");
        myFixture.addClass("package java.util.stream; public interface Stream<T> {}");
        myFixture.addClass("package org.springframework.data.repository;"
                + " public interface Repository<T, ID> {}");
        myFixture.addClass("package org.springframework.data.jpa.repository;"
                + " import org.springframework.data.repository.Repository;"
                + " public interface JpaRepository<T, ID> extends Repository<T, ID> {}");
        myFixture.addClass("package org.springframework.data.jpa.repository;"
                + " public @interface Query { String value() default \"\"; boolean nativeQuery() default false; }");
        myFixture.addClass("package org.springframework.data.domain; public interface Pageable {}");
        myFixture.addClass("package org.springframework.data.domain; public interface Sort {}");
        myFixture.addClass("package org.springframework.data.domain; public interface Page<T> {}");
        myFixture.addClass("package org.springframework.data.domain; public interface Slice<T> {}");
        myFixture.addClass("package com.example; public class User {}");
        myFixture.enableInspections(inspection);
    }

    private List<HighlightInfo> repo(String body) {
        myFixture.configureByText("UserRepository.java",
                "import java.util.List;\n"
                        + "import java.util.stream.Stream;\n"
                        + "import org.springframework.data.jpa.repository.JpaRepository;\n"
                        + "import org.springframework.data.jpa.repository.Query;\n"
                        + "import org.springframework.data.domain.Pageable;\n"
                        + "import org.springframework.data.domain.Sort;\n"
                        + "import org.springframework.data.domain.Page;\n"
                        + "import org.springframework.data.domain.Slice;\n"
                        + "import com.example.User;\n"
                        + "interface UserRepository extends JpaRepository<User, Long> {\n" + body + "\n}\n");
        return myFixture.doHighlighting();
    }

    private long warnings(List<HighlightInfo> infos) {
        return infos.stream()
                .filter(i -> i.getSeverity() == HighlightSeverity.WARNING)
                .filter(i -> i.getDescription() != null && i.getDescription().contains("without pagination"))
                .count();
    }

    // ── positive ──────────────────────────────────────────────────────────────

    public void testUnboundedListFindAll_isFlagged() {
        assertEquals(1, warnings(repo("  List<User> findAllByStatus(String status);")));
    }

    public void testUnboundedStreamFindAll_isFlagged() {
        assertEquals(1, warnings(repo("  Stream<User> getAllUsers();")));
    }

    // ── suppression ─────────────────────────────────────────────────────────--

    public void testPageReturn_notFlagged() {
        assertEquals(0, warnings(repo("  Page<User> findAllByStatus(String status, Pageable pageable);")));
    }

    public void testSliceReturn_notFlagged() {
        assertEquals(0, warnings(repo("  Slice<User> findAllByStatus(String status, Pageable pageable);")));
    }

    public void testPageableParam_notFlagged() {
        assertEquals(0, warnings(repo("  List<User> findAllByStatus(String status, Pageable pageable);")));
    }

    public void testSortParam_notFlagged() {
        assertEquals(0, warnings(repo("  List<User> findAllByStatus(String status, Sort sort);")));
    }

    public void testTopKeyword_notFlagged() {
        assertEquals(0, warnings(repo("  List<User> findTop10ByOrderByIdDesc();")));
    }

    public void testQueryWithLimit_notFlagged() {
        assertEquals(0, warnings(repo(
                "  @Query(value = \"SELECT * FROM users LIMIT 50\", nativeQuery = true)\n"
                        + "  List<User> findAllRecent();")));
    }

    public void testSpecificFinder_notFlaggedByDefault() {
        // onlyWarnForFindAll defaults true; this name has no 'All' segment
        assertEquals(0, warnings(repo("  List<User> findByStatus(String status);")));
    }

    public void testSpecificFinder_flaggedWhenOptionWidened() {
        inspection.onlyWarnForFindAll = false;
        assertEquals(1, warnings(repo("  List<User> findByStatus(String status);")));
    }

    public void testNonRepositoryInterface_notFlagged() {
        myFixture.configureByText("NotARepo.java",
                "import java.util.List;\n"
                        + "import com.example.User;\n"
                        + "interface NotARepo {\n"
                        + "  List<User> findAllByStatus(String status);\n"
                        + "}\n");
        assertEquals(0, warnings(myFixture.doHighlighting()));
    }

    // ── quick-fix ─────────────────────────────────────────────────────────────

    public void testConvertToPageQuickFix() {
        // caret inside the return type element, which is where the warning is anchored
        myFixture.configureByText("UserRepository.java",
                "import java.util.List;\n"
                        + "import org.springframework.data.jpa.repository.JpaRepository;\n"
                        + "import com.example.User;\n"
                        + "interface UserRepository extends JpaRepository<User, Long> {\n"
                        + "  Li<caret>st<User> findAllByStatus(String status);\n"
                        + "}\n");
        IntentionAction fix = myFixture.findSingleIntention("Return Page<…> and add a Pageable parameter");
        assertNotNull(fix);
        myFixture.launchAction(fix);

        String text = myFixture.getFile().getText();
        assertTrue(text, text.contains("Page<User>"));
        assertTrue(text, text.contains("Pageable pageable"));
        assertFalse(text, text.contains("List<User>"));
    }
}
