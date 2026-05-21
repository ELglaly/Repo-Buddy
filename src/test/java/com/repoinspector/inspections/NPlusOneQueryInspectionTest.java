package com.repoinspector.inspections;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;

import java.util.List;

/**
 * Fixture (integration) tests for {@link NPlusOneQueryInspection}.
 */
public class NPlusOneQueryInspectionTest extends LightJavaCodeInsightFixtureTestCase {

    // alwaysAnalyze = true bypasses panel-only gating so these tests exercise the analysis logic.
    private final NPlusOneQueryInspection inspection = new NPlusOneQueryInspection(true);

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        // JPA / Hibernate annotation stubs
        myFixture.addClass("package jakarta.persistence; public enum FetchType { LAZY, EAGER }");
        myFixture.addClass("package jakarta.persistence; public @interface Entity {}");
        myFixture.addClass("package jakarta.persistence;"
                + " public @interface OneToMany { FetchType fetch() default FetchType.LAZY; }");
        myFixture.addClass("package jakarta.persistence;"
                + " public @interface ManyToMany { FetchType fetch() default FetchType.LAZY; }");
        myFixture.addClass("package jakarta.persistence;"
                + " public @interface ManyToOne { FetchType fetch() default FetchType.EAGER; }");
        myFixture.addClass("package jakarta.persistence;"
                + " public @interface OneToOne { FetchType fetch() default FetchType.EAGER; }");
        myFixture.addClass("package org.hibernate.annotations; public @interface BatchSize { int size(); }");
        // JDK collection stubs (light test JDK does not resolve java.util)
        myFixture.addClass("package java.util; public interface Collection<E> extends Iterable<E> {}");
        myFixture.addClass("package java.util;"
                + " public interface List<E> extends Collection<E> { int size(); E get(int index); }");
        myFixture.addClass("package java.util; public final class Optional<T> {}");

        myFixture.addClass("package com.example; import jakarta.persistence.Entity;"
                + " @Entity public class Company { private Long id; public Long getId() { return id; } }");

        myFixture.addClass("package com.example;"
                + " import java.util.List;"
                + " import jakarta.persistence.*;"
                + " import org.hibernate.annotations.BatchSize;"
                + " @Entity public class User {"
                + "   private Long id;"
                + "   @OneToMany private List<Object> addresses;"
                + "   @ManyToMany private List<Object> roles;"
                + "   @ManyToOne private Company company;"
                + "   @ManyToOne(fetch = FetchType.LAZY) private Company manager;"
                + "   @OneToMany(fetch = FetchType.EAGER) private List<Object> tags;"
                + "   @OneToMany @BatchSize(size = 50) private List<Object> orders;"
                + "   public Long getId() { return id; }"
                + "   public List<Object> getAddresses() { return addresses; }"
                + "   public List<Object> getRoles() { return roles; }"
                + "   public Company getCompany() { return company; }"
                + "   public Company getManager() { return manager; }"
                + "   public List<Object> getTags() { return tags; }"
                + "   public List<Object> getOrders() { return orders; }"
                + " }");

        // Same shape but NOT an @Entity, to verify the entity gate
        myFixture.addClass("package com.example;"
                + " import java.util.List;"
                + " import jakarta.persistence.OneToMany;"
                + " public class PlainUser {"
                + "   @OneToMany private List<Object> addresses;"
                + "   public List<Object> getAddresses() { return addresses; }"
                + " }");

        // Spring Data repository stubs
        myFixture.addClass("package org.springframework.data.repository;"
                + " public interface Repository<T, ID> {}");
        myFixture.addClass("package org.springframework.data.jpa.repository;"
                + " import org.springframework.data.repository.Repository;"
                + " import java.util.List; import java.util.Optional;"
                + " public interface JpaRepository<T, ID> extends Repository<T, ID> {"
                + "   Optional<T> findById(ID id);"
                + "   List<T> findAll();"
                + "   List<T> findAllById(Iterable<ID> ids);"
                + "   <S extends T> S save(S entity);"
                + "   void delete(T entity); }");
        myFixture.addClass("package com.example;"
                + " import org.springframework.data.jpa.repository.JpaRepository;"
                + " public interface UserRepository extends JpaRepository<User, Long> {}");

        // JPA EntityManager stubs
        myFixture.addClass("package jakarta.persistence; public interface Query {"
                + " Object getResultList(); Object getSingleResult(); }");
        myFixture.addClass("package jakarta.persistence; public interface EntityManager {"
                + " <T> T find(Class<T> type, Object id); Query createQuery(String ql); }");

        myFixture.enableInspections(inspection);
    }

    private List<HighlightInfo> repoInLoop(String loopBody) {
        myFixture.configureByText("Service.java",
                "import java.util.List;\n"
                        + "import com.example.User;\n"
                        + "import com.example.UserRepository;\n"
                        + "import jakarta.persistence.EntityManager;\n"
                        + "class Service {\n"
                        + "  UserRepository repo;\n"
                        + "  EntityManager em;\n"
                        + "  void process(List<User> users) {\n"
                        + "    for (User user : users) {\n"
                        + "      " + loopBody + "\n"
                        + "    }\n"
                        + "  }\n"
                        + "}\n");
        return myFixture.doHighlighting();
    }

    private List<HighlightInfo> repoInService(String methodBody) {
        myFixture.configureByText("Service.java",
                "import java.util.List;\n"
                        + "import com.example.User;\n"
                        + "import com.example.UserRepository;\n"
                        + "import jakarta.persistence.EntityManager;\n"
                        + "class Service {\n"
                        + "  UserRepository repo;\n"
                        + "  EntityManager em;\n"
                        + "  void process(List<User> users) {\n"
                        + "    " + methodBody + "\n"
                        + "  }\n"
                        + "}\n");
        return myFixture.doHighlighting();
    }

    private List<HighlightInfo> inLoop(String loopBody) {
        myFixture.configureByText("Service.java",
                "import java.util.List;\n"
                        + "import com.example.User;\n"
                        + "class Service {\n"
                        + "  void process(List<User> users) {\n"
                        + "    for (User user : users) {\n"
                        + "      " + loopBody + "\n"
                        + "    }\n"
                        + "  }\n"
                        + "}\n");
        return myFixture.doHighlighting();
    }

    private List<HighlightInfo> inMethod(String body) {
        myFixture.configureByText("Service.java",
                "import java.util.List;\n"
                        + "import com.example.User;\n"
                        + "class Service {\n"
                        + "  void process(List<User> users) {\n"
                        + "    " + body + "\n"
                        + "  }\n"
                        + "}\n");
        return myFixture.doHighlighting();
    }

    private long warnings(List<HighlightInfo> infos) {
        return infos.stream()
                .filter(i -> i.getSeverity() == HighlightSeverity.WARNING)
                .filter(i -> i.getDescription() != null && i.getDescription().contains("Potential N+1"))
                .count();
    }

    // ── flagged ───────────────────────────────────────────────────────────────

    public void testLazyOneToMany_flagged() {
        assertEquals(1, warnings(inLoop("user.getAddresses();")));
    }

    public void testLazyManyToMany_flagged() {
        assertEquals(1, warnings(inLoop("user.getRoles();")));
    }

    public void testLazyManyToOne_flagged() {
        assertEquals(1, warnings(inLoop("user.getManager();")));
    }

    public void testLazyAccessChained_flagged() {
        assertEquals(1, warnings(inLoop("user.getAddresses().toString();")));
    }

    // ── not flagged ─────────────────────────────────────────────────────────--

    public void testEagerManyToOne_notFlagged() {
        assertEquals(0, warnings(inLoop("user.getCompany();")));
    }

    public void testEagerOneToMany_notFlagged() {
        assertEquals(0, warnings(inLoop("user.getTags();")));
    }

    public void testIdGetter_notFlagged() {
        assertEquals(0, warnings(inLoop("user.getId();")));
    }

    public void testBatchSizeSuppresses() {
        assertEquals(0, warnings(inLoop("user.getOrders();")));
    }

    public void testBatchSizeBelowThreshold_flagged() {
        inspection.batchSizeThreshold = 100;
        assertEquals(1, warnings(inLoop("user.getOrders();")));
    }

    public void testNonEntityLoopVariable_notFlagged() {
        myFixture.configureByText("Service.java",
                "import java.util.List;\n"
                        + "import com.example.PlainUser;\n"
                        + "class Service {\n"
                        + "  void process(List<PlainUser> users) {\n"
                        + "    for (PlainUser user : users) {\n"
                        + "      user.getAddresses();\n"
                        + "    }\n"
                        + "  }\n"
                        + "}\n");
        assertEquals(0, warnings(myFixture.doHighlighting()));
    }

    // ── stream / lambda iteration ─────────────────────────────────────────────

    public void testForEachLambdaLazy_flagged() {
        assertEquals(1, warnings(
                inMethod("users.forEach((User user) -> user.getAddresses());")));
    }

    public void testStreamMapLazy_flagged() {
        assertEquals(1, warnings(
                inMethod("users.stream().map((User user) -> user.getRoles());")));
    }

    public void testStreamLazyManyToOne_flagged() {
        assertEquals(1, warnings(
                inMethod("users.stream().filter((User user) -> user.getManager() != null);")));
    }

    public void testForEachLambdaEager_notFlagged() {
        assertEquals(0, warnings(
                inMethod("users.forEach((User user) -> user.getCompany());")));
    }

    public void testForEachLambdaIdGetter_notFlagged() {
        assertEquals(0, warnings(
                inMethod("users.forEach((User user) -> user.getId());")));
    }

    public void testNonIterationMethodLambda_notFlagged() {
        myFixture.configureByText("Service.java",
                "import java.util.List;\n"
                        + "import com.example.User;\n"
                        + "class Service {\n"
                        + "  interface Fn { Object apply(User u); }\n"
                        + "  Object apply(Fn fn) { return null; }\n"
                        + "  void process(List<User> users) {\n"
                        + "    apply((User user) -> user.getAddresses());\n"
                        + "  }\n"
                        + "}\n");
        assertEquals(0, warnings(myFixture.doHighlighting()));
    }

    public void testAccessOnNonLoopVariable_notFlagged() {
        myFixture.configureByText("Service.java",
                "import java.util.List;\n"
                        + "import com.example.User;\n"
                        + "class Service {\n"
                        + "  private User other;\n"
                        + "  void process(List<User> users) {\n"
                        + "    for (User user : users) {\n"
                        + "      other.getAddresses();\n"
                        + "    }\n"
                        + "  }\n"
                        + "}\n");
        assertEquals(0, warnings(myFixture.doHighlighting()));
    }

    // ── query call in loop ─────────────────────────────────────────────────────

    public void testRepoFindByIdInForEach_flagged() {
        assertEquals(1, warnings(repoInLoop("repo.findById(user.getId());")));
    }

    public void testRepoSaveInForEach_flagged() {
        assertEquals(1, warnings(repoInLoop("repo.save(user);")));
    }

    public void testEntityManagerFindInForEach_flagged() {
        assertEquals(1, warnings(repoInLoop("em.find(User.class, user.getId());")));
    }

    public void testEntityManagerQueryGetResultListInForEach_flagged() {
        assertEquals(1, warnings(repoInLoop("em.createQuery(\"x\").getResultList();")));
    }

    public void testRepoCallOutsideLoop_notFlagged() {
        assertEquals(0, warnings(repoInService("repo.findAllById(null);")));
    }

    public void testRepoCallAsLoopSource_notFlagged() {
        myFixture.configureByText("Service.java",
                "import java.util.List;\n"
                        + "import com.example.User;\n"
                        + "import com.example.UserRepository;\n"
                        + "class Service {\n"
                        + "  UserRepository repo;\n"
                        + "  void process() {\n"
                        + "    for (User user : repo.findAll()) {\n"
                        + "      user.getId();\n"
                        + "    }\n"
                        + "  }\n"
                        + "}\n");
        assertEquals(0, warnings(myFixture.doHighlighting()));
    }

    public void testRepoCallInIndexedFor_flagged() {
        assertEquals(1, warnings(repoInService(
                "for (int i = 0; i < users.size(); i++) { repo.findById(users.get(i).getId()); }")));
    }

    public void testRepoCallInWhile_flagged() {
        assertEquals(1, warnings(repoInService(
                "int i = 0; while (i < users.size()) { repo.findById(users.get(i).getId()); i++; }")));
    }

    public void testRepoCallInStreamMap_flagged() {
        assertEquals(1, warnings(repoInService(
                "users.stream().map((User user) -> repo.findById(user.getId()));")));
    }

    public void testRepoMethodReferenceInForEach_flagged() {
        assertEquals(1, warnings(repoInService("users.forEach(repo::delete);")));
    }

    public void testRepoCallInDeferredLambda_notFlagged() {
        // A non-iteration lambda is a separate execution context, not a per-iteration loop body.
        assertEquals(0, warnings(repoInService(
                "Runnable r = () -> repo.findAll();")));
    }

    public void testNonDataAccessCallInLoop_notFlagged() {
        assertEquals(0, warnings(repoInLoop("user.getId();")));
    }

    public void testQueryInLoopDisabled_notFlagged() {
        inspection.reportQueryCallsInLoops = false;
        assertEquals(0, warnings(repoInLoop("repo.findById(user.getId());")));
    }

    // ── lazy access via element access in indexed-for / while ──────────────────

    public void testLazyAccessIndexedForViaGet_flagged() {
        assertEquals(1, warnings(repoInService(
                "for (int i = 0; i < users.size(); i++) { users.get(i).getAddresses(); }")));
    }

    public void testLazyAccessWhileViaGet_flagged() {
        assertEquals(1, warnings(repoInService(
                "int i = 0; while (i < users.size()) { users.get(i).getRoles(); i++; }")));
    }

    public void testEagerAccessIndexedFor_notFlagged() {
        assertEquals(0, warnings(repoInService(
                "for (int i = 0; i < users.size(); i++) { users.get(i).getCompany(); }")));
    }

    public void testIdGetterIndexedFor_notFlagged() {
        assertEquals(0, warnings(repoInService(
                "for (int i = 0; i < users.size(); i++) { users.get(i).getId(); }")));
    }

    public void testElementAccessOutsideLoop_notFlagged() {
        assertEquals(0, warnings(repoInService("users.get(0).getAddresses();")));
    }
}
