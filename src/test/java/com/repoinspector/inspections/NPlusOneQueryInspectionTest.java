package com.repoinspector.inspections;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;

import java.util.List;

/**
 * Fixture (integration) tests for {@link NPlusOneQueryInspection}.
 */
public class NPlusOneQueryInspectionTest extends LightJavaCodeInsightFixtureTestCase {

    private final NPlusOneQueryInspection inspection = new NPlusOneQueryInspection();

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
        myFixture.addClass("package java.util; public interface Collection<E> {}");
        myFixture.addClass("package java.util; public interface List<E> extends Collection<E> {}");

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

        myFixture.enableInspections(inspection);
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
}
