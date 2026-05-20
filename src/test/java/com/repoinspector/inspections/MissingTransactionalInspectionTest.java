package com.repoinspector.inspections;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;

import java.util.List;

/**
 * Fixture (integration) tests for {@link MissingTransactionalInspection}.
 */
public class MissingTransactionalInspectionTest extends LightJavaCodeInsightFixtureTestCase {

    private final MissingTransactionalInspection inspection = new MissingTransactionalInspection();

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.addClass("package jakarta.persistence; public interface Query {"
                + " int executeUpdate(); Object getResultList(); }");
        myFixture.addClass("package jakarta.persistence; public interface EntityManager {"
                + " void persist(Object e); <T> T merge(T e); void remove(Object e); void flush();"
                + " Query createQuery(String ql); }");
        myFixture.addClass("package org.springframework.transaction.annotation;"
                + " public @interface Transactional {}");
        myFixture.addClass("package org.springframework.data.jpa.repository;"
                + " public @interface Modifying {}");
        myFixture.addClass("package org.springframework.data.repository;"
                + " public interface Repository<T, ID> {}");
        myFixture.addClass("package org.springframework.data.jpa.repository;"
                + " import org.springframework.data.repository.Repository;"
                + " public interface JpaRepository<T, ID> extends Repository<T, ID> {"
                + " <S extends T> S save(S entity); void delete(T entity); }");
        myFixture.addClass("package com.example; public class User {}");
        myFixture.enableInspections(inspection);
    }

    private List<HighlightInfo> highlight(String decl) {
        myFixture.configureByText("Sample.java",
                "import jakarta.persistence.EntityManager;\n"
                        + "import jakarta.persistence.Query;\n"
                        + "import org.springframework.transaction.annotation.Transactional;\n"
                        + "import org.springframework.data.jpa.repository.Modifying;\n"
                        + "import org.springframework.data.jpa.repository.JpaRepository;\n"
                        + "import com.example.User;\n" + decl + "\n");
        return myFixture.doHighlighting();
    }

    private long warnings(List<HighlightInfo> infos, String needle) {
        return infos.stream()
                .filter(i -> i.getSeverity() == HighlightSeverity.WARNING)
                .filter(i -> i.getDescription() != null && i.getDescription().contains(needle))
                .count();
    }

    // ── Check A: @Modifying ─────────────────────────────────────────────────--

    public void testModifyingWithoutTransactional_isFlagged() {
        assertEquals(1, warnings(
                highlight("interface UserRepo { @Modifying int bulkDeactivate(); }"),
                "@Modifying query method"));
    }

    public void testModifyingWithMethodTransactional_notFlagged() {
        assertEquals(0, warnings(
                highlight("interface UserRepo { @Modifying @Transactional int bulkDeactivate(); }"),
                "@Modifying query method"));
    }

    public void testModifyingWithClassTransactional_notFlagged() {
        assertEquals(0, warnings(
                highlight("@Transactional interface UserRepo { @Modifying int bulkDeactivate(); }"),
                "@Modifying query method"));
    }

    // ── Check B: EntityManager writes ─────────────────────────────────────────

    public void testExecuteUpdateWithoutTransactional_isFlagged() {
        assertEquals(1, warnings(
                highlight("class UserDao { private EntityManager em;"
                        + " void deleteAll() { em.createQuery(\"DELETE FROM User u\").executeUpdate(); } }"),
                "JPA write"));
    }

    public void testPersistWithoutTransactional_isFlagged() {
        assertEquals(1, warnings(
                highlight("class UserDao { private EntityManager em;"
                        + " void add(User u) { em.persist(u); } }"),
                "JPA write"));
    }

    public void testEntityManagerWriteWithMethodTransactional_notFlagged() {
        assertEquals(0, warnings(
                highlight("class UserDao { private EntityManager em;"
                        + " @Transactional void add(User u) { em.persist(u); } }"),
                "JPA write"));
    }

    public void testEntityManagerWriteWithClassTransactional_notFlagged() {
        assertEquals(0, warnings(
                highlight("@Transactional class UserDao { private EntityManager em;"
                        + " void add(User u) { em.persist(u); } }"),
                "JPA write"));
    }

    public void testPrivateMethod_notFlaggedByDefault() {
        assertEquals(0, warnings(
                highlight("class UserDao { private EntityManager em;"
                        + " private void add(User u) { em.persist(u); } }"),
                "JPA write"));
    }

    public void testReadOnlyQuery_notFlagged() {
        assertEquals(0, warnings(
                highlight("class UserDao { private EntityManager em;"
                        + " Object all() { return em.createQuery(\"SELECT u FROM User u\").getResultList(); } }"),
                "JPA write"));
    }

    // ── Check C: repository write calls (opt-in) ──────────────────────────────

    public void testRepositorySave_flaggedByDefault() {
        assertEquals(1, warnings(
                highlight("interface UserRepository extends JpaRepository<User, Long> {}\n"
                        + "class UserService { private UserRepository repo;"
                        + " void create(User u) { repo.save(u); } }"),
                "repository write operations"));
    }

    public void testRepositorySave_notFlaggedWhenDisabled() {
        inspection.includeRepositoryWriteCalls = false;
        assertEquals(0, warnings(
                highlight("interface UserRepository extends JpaRepository<User, Long> {}\n"
                        + "class UserService { private UserRepository repo;"
                        + " void create(User u) { repo.save(u); } }"),
                "repository write operations"));
    }

    public void testEntitySetterAndCollectionAdd_notCountedAsRepositoryWrite() {
        // setQuantity()/items.add() are write-prefixed names but their owners are not
        // repositories, so they must not trigger the repository-write check.
        assertEquals(0, warnings(
                highlight("class CartItem { private int q; public void setQuantity(int q){this.q=q;} }\n"
                        + "class CartService { void touch(CartItem item) { item.setQuantity(5); } }"),
                "repository write operations"));
    }

    // ── quick-fix ─────────────────────────────────────────────────────────────

    public void testAddTransactionalQuickFix() {
        myFixture.configureByText("UserDao.java",
                "import jakarta.persistence.EntityManager;\n"
                        + "class UserDao {\n"
                        + "  private EntityManager em;\n"
                        + "  void add<caret>(Object u) { em.persist(u); }\n"
                        + "}\n");
        IntentionAction fix = myFixture.findSingleIntention("Annotate method with @Transactional");
        assertNotNull(fix);
        myFixture.launchAction(fix);

        String text = myFixture.getFile().getText();
        assertTrue(text, text.contains("@Transactional"));
        assertTrue(text, text.contains("import org.springframework.transaction.annotation.Transactional"));
    }
}
