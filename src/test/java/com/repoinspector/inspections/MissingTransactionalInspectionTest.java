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

    // alwaysAnalyze = true bypasses panel-only gating so these tests exercise the analysis logic.
    private final MissingTransactionalInspection inspection = new MissingTransactionalInspection(true);

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
        myFixture.addClass("package org.springframework.transaction; public interface TransactionStatus {}");
        myFixture.addClass("package org.springframework.transaction; public interface TransactionDefinition {}");
        myFixture.addClass("package org.springframework.transaction; public interface PlatformTransactionManager {"
                + " TransactionStatus getTransaction(TransactionDefinition definition);"
                + " void commit(TransactionStatus status); void rollback(TransactionStatus status); }");
        myFixture.addClass("package org.springframework.transaction.support;"
                + " import org.springframework.transaction.TransactionStatus;"
                + " public interface TransactionCallback<T> { T doInTransaction(TransactionStatus status); }");
        myFixture.addClass("package org.springframework.transaction.support;"
                + " import java.util.function.Consumer;"
                + " import org.springframework.transaction.TransactionStatus;"
                + " public interface TransactionOperations {"
                + "  <T> T execute(TransactionCallback<T> action);"
                + "  default void executeWithoutResult(Consumer<TransactionStatus> action) {}"
                + " }");
        myFixture.addClass("package org.springframework.transaction.support;"
                + " public class TransactionTemplate implements TransactionOperations {"
                + "  public <T> T execute(TransactionCallback<T> action) { return null; }"
                + " }");
        myFixture.addClass("package org.reactivestreams; public interface Publisher<T> {}");
        myFixture.addClass("package reactor.core.publisher;"
                + " import java.util.function.Function; import org.reactivestreams.Publisher;"
                + " public class Mono<T> implements Publisher<T> {"
                + "  public <P> P as(Function<? super Mono<T>, ? extends P> transform) { return null; } }");
        myFixture.addClass("package org.springframework.transaction.reactive; public interface ReactiveTransaction {}");
        myFixture.addClass("package org.springframework.transaction.reactive;"
                + " import java.util.function.Function; import org.reactivestreams.Publisher;"
                + " public interface TransactionalOperator {"
                + "  <T> Publisher<T> execute(Function<ReactiveTransaction, Publisher<T>> callback);"
                + "  <T> Publisher<T> transactional(Publisher<T> publisher); }");
        myFixture.addClass("package org.springframework.data.jpa.repository;"
                + " public @interface Modifying {}");
        myFixture.addClass("package org.springframework.data.repository;"
                + " public interface Repository<T, ID> {}");
        myFixture.addClass("package org.springframework.data.jpa.repository;"
                + " import org.springframework.data.repository.Repository;"
                + " public interface JpaRepository<T, ID> extends Repository<T, ID> {"
                + " <S extends T> S save(S entity); <S extends T> Iterable<S> saveAll(Iterable<S> entities);"
                + " void delete(T entity); void deleteById(ID id); T findById(ID id); }");
        myFixture.addClass("package org.hibernate; public interface Session {"
                + " void save(Object e); void delete(Object e); void persist(Object e); }");
        myFixture.addClass("package org.springframework.jdbc.core; public class JdbcTemplate {"
                + " public int update(String sql) { return 0; } public void execute(String sql) {} }");
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
                "database write"));
    }

    public void testPersistWithoutTransactional_isFlagged() {
        assertEquals(1, warnings(
                highlight("class UserDao { private EntityManager em;"
                        + " void add(User u) { em.persist(u); } }"),
                "database write"));
    }

    public void testEntityManagerWriteWithMethodTransactional_notFlagged() {
        assertEquals(0, warnings(
                highlight("class UserDao { private EntityManager em;"
                        + " @Transactional void add(User u) { em.persist(u); } }"),
                "database write"));
    }

    public void testEntityManagerWriteWithClassTransactional_notFlagged() {
        assertEquals(0, warnings(
                highlight("@Transactional class UserDao { private EntityManager em;"
                        + " void add(User u) { em.persist(u); } }"),
                "database write"));
    }

    public void testPrivateMethod_notFlaggedByDefault() {
        assertEquals(0, warnings(
                highlight("class UserDao { private EntityManager em;"
                        + " private void add(User u) { em.persist(u); } }"),
                "database write"));
    }

    public void testReadOnlyQuery_notFlagged() {
        assertEquals(0, warnings(
                highlight("class UserDao { private EntityManager em;"
                        + " Object all() { return em.createQuery(\"SELECT u FROM User u\").getResultList(); } }"),
                "database write"));
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

    // ── Check D: JDBC + Hibernate-native writes ───────────────────────────────

    public void testJdbcUpdateWithoutTransactional_isFlagged() {
        assertEquals(1, warnings(
                highlight("class UserDao { private org.springframework.jdbc.core.JdbcTemplate jdbc;"
                        + " void purge() { jdbc.update(\"DELETE FROM users\"); } }"),
                "database write"));
    }

    public void testJdbcUpdateWithTransactional_notFlagged() {
        assertEquals(0, warnings(
                highlight("class UserDao { private org.springframework.jdbc.core.JdbcTemplate jdbc;"
                        + " @Transactional void purge() { jdbc.update(\"DELETE FROM users\"); } }"),
                "database write"));
    }

    public void testHibernateSaveWithoutTransactional_isFlagged() {
        assertEquals(1, warnings(
                highlight("class UserDao { private org.hibernate.Session session;"
                        + " void add(User u) { session.save(u); } }"),
                "database write"));
    }

    // ── Check E: static methods ───────────────────────────────────────────────

    public void testStaticMethodWithWrite_flaggedAsStatic() {
        assertEquals(1, warnings(
                highlight("class UserDao { private static EntityManager em;"
                        + " static void add(User u) { em.persist(u); } }"),
                "static"));
    }

    public void testStaticMethodWithWrite_noQuickFix() {
        myFixture.configureByText("UserDao.java",
                "import jakarta.persistence.EntityManager;\n"
                        + "class UserDao {\n"
                        + "  private static EntityManager em;\n"
                        + "  static void add<caret>(Object u) { em.persist(u); }\n"
                        + "}\n");
        myFixture.doHighlighting();
        assertEmpty(myFixture.filterAvailableIntentions("Annotate method with @Transactional"));
    }

    // ── Check F: transitive write through a private helper ────────────────────

    public void testTransitiveWriteThroughPrivateHelper_flagged() {
        assertEquals(1, warnings(
                highlight("class Svc { private EntityManager em;"
                        + " void create(User u) { doSave(u); }"
                        + " private void doSave(User u) { em.persist(u); } }"),
                "private helper"));
    }

    public void testTransitiveWriteTwoHopsThroughPrivateHelpers_flagged() {
        assertEquals(1, warnings(
                highlight("class Svc { private EntityManager em;"
                        + " void create(User u) { step1(u); }"
                        + " private void step1(User u) { step2(u); }"
                        + " private void step2(User u) { em.persist(u); } }"),
                "private helper"));
    }

    public void testTransitiveNoWrite_notFlagged() {
        assertEquals(0, warnings(
                highlight("class Svc { private EntityManager em;"
                        + " void create() { compute(); }"
                        + " private void compute() { int x = 1; } }"),
                "private helper"));
    }

    public void testTransitiveDisabled_notFlagged() {
        inspection.analyzeCalledMethods = false;
        assertEquals(0, warnings(
                highlight("class Svc { private EntityManager em;"
                        + " void create(User u) { doSave(u); }"
                        + " private void doSave(User u) { em.persist(u); } }"),
                "private helper"));
    }

    // Programmatic Spring transaction boundaries

    public void testTransactionTemplateExecute_directWrite_notFlagged() {
        assertEquals(0, warnings(highlight(
                "interface UserRepository extends JpaRepository<User, Long> {}\n"
                        + "class Svc { UserRepository repo; org.springframework.transaction.support.TransactionTemplate tx;"
                        + " User create(User u) { return tx.execute(status -> { repo.save(u); return u; }); } }"),
                "repository write operations"));
    }

    public void testTransactionTemplateExecuteWithoutResult_directWrite_notFlagged() {
        assertEquals(0, warnings(highlight(
                "interface UserRepository extends JpaRepository<User, Long> {}\n"
                        + "class Svc { UserRepository repo; org.springframework.transaction.support.TransactionTemplate tx;"
                        + " void delete(Long id) { tx.executeWithoutResult(status -> repo.deleteById(id)); } }"),
                "repository write operations"));
    }

    public void testTransactionOperations_directWrite_notFlagged() {
        assertEquals(0, warnings(highlight(
                "interface UserRepository extends JpaRepository<User, Long> {}\n"
                        + "class Svc { UserRepository repo; org.springframework.transaction.support.TransactionOperations transactions;"
                        + " void delete(Long id) { transactions.executeWithoutResult(status -> repo.deleteById(id)); } }"),
                "repository write operations"));
    }

    public void testTransactionTemplateCallbackThroughPrivateHelper_notFlagged() {
        assertEquals(0, warnings(highlight(
                "interface UserRepository extends JpaRepository<User, Long> {}\n"
                        + "class Svc { UserRepository repo; org.springframework.transaction.support.TransactionTemplate tx;"
                        + " void purge(Long id) { tx.executeWithoutResult(status -> deleteRow(id)); }"
                        + " private void deleteRow(Long id) { repo.deleteById(id); } }"),
                "private helper"));
    }

    public void testAttachmentSweeperStyleOuterHelperAndNarrowTransactions_notFlagged() {
        assertEquals(0, warnings(highlight(
                "interface AttachmentRepository extends JpaRepository<User, Long> {"
                        + " java.lang.Iterable<User> findAbandoned(); }\n"
                        + "class Storage { void delete(String key) {} }\n"
                        + "class AttachmentSweeper { AttachmentRepository repository; Storage storage;"
                        + " org.springframework.transaction.support.TransactionTemplate transactionTemplate;"
                        + " void sweep() { purge(repository.findAbandoned()); }"
                        + " private int purge(java.lang.Iterable<User> candidates) {"
                        + "  int deleted = 0; for (User candidate : candidates) { storage.delete(\"key\");"
                        + "   transactionTemplate.executeWithoutResult(status -> repository.delete(candidate));"
                        + "   deleted++; } return deleted; } }"),
                "private helper"));
    }

    public void testTransactionTemplateMethodReferenceThroughPrivateHelper_notFlagged() {
        assertEquals(0, warnings(highlight(
                "interface UserRepository extends JpaRepository<User, Long> {}\n"
                        + "class Svc { UserRepository repo; org.springframework.transaction.support.TransactionTemplate tx;"
                        + " void purge() { tx.executeWithoutResult(this::deleteRows); }"
                        + " private void deleteRows(org.springframework.transaction.TransactionStatus status) { repo.deleteById(1L); } }"),
                "private helper"));
    }

    public void testTransactionTemplateExecuteMethodReference_notFlagged() {
        assertEquals(0, warnings(highlight(
                "interface UserRepository extends JpaRepository<User, Long> {}\n"
                        + "class Svc { UserRepository repo; org.springframework.transaction.support.TransactionTemplate tx;"
                        + " User purge() { return tx.execute(this::performDatabaseWork); }"
                        + " private User performDatabaseWork(org.springframework.transaction.TransactionStatus status) {"
                        + "  User user = new User(); repo.save(user); return user; } }"),
                "private helper"));
    }

    public void testMultipleWritesInsideTransactionTemplate_notFlagged() {
        assertEquals(0, warnings(highlight(
                "interface UserRepository extends JpaRepository<User, Long> {}\n"
                        + "class Svc { UserRepository repo; org.springframework.transaction.support.TransactionTemplate tx;"
                        + " void purge(User u) { tx.executeWithoutResult(status -> {"
                        + " repo.deleteById(1L); repo.deleteById(2L); repo.save(u); }); } }"),
                "repository write operations"));
    }

    public void testExternalOperationOutsideAndWriteInsideTransactionTemplate_notFlagged() {
        assertEquals(0, warnings(highlight(
                "interface UserRepository extends JpaRepository<User, Long> {}\n"
                        + "class Storage { void delete(String key) {} }\n"
                        + "class Svc { UserRepository repo; Storage storage; org.springframework.transaction.support.TransactionTemplate tx;"
                        + " void purge() { storage.delete(\"key\");"
                        + " tx.executeWithoutResult(status -> repo.deleteById(1L)); } }"),
                "repository write operations"));
    }

    public void testRepositoryReadOutsideAndWriteInsideTransactionTemplate_notFlagged() {
        assertEquals(0, warnings(highlight(
                "interface UserRepository extends JpaRepository<User, Long> {}\n"
                        + "class Svc { UserRepository repo; org.springframework.transaction.support.TransactionTemplate tx;"
                        + " void purge() { User user = repo.findById(1L);"
                        + " tx.executeWithoutResult(status -> repo.delete(user)); } }"),
                "repository write operations"));
    }

    public void testTransactionTemplateFieldWithoutBoundary_stillFlagged() {
        assertEquals(1, warnings(highlight(
                "interface UserRepository extends JpaRepository<User, Long> {}\n"
                        + "class Svc { UserRepository repo; org.springframework.transaction.support.TransactionTemplate tx;"
                        + " void broken(Long id) { repo.deleteById(id); } }"),
                "repository write operations"));
    }

    public void testWriteBeforeTransactionTemplate_stillFlagged() {
        assertEquals(1, warnings(highlight(
                "interface UserRepository extends JpaRepository<User, Long> {}\n"
                        + "class Svc { UserRepository repo; org.springframework.transaction.support.TransactionTemplate tx;"
                        + " void broken(User u) { repo.deleteById(1L);"
                        + " tx.executeWithoutResult(status -> repo.save(u)); } }"),
                "repository write operations"));
    }

    public void testWriteAfterTransactionTemplate_stillFlagged() {
        assertEquals(1, warnings(highlight(
                "interface UserRepository extends JpaRepository<User, Long> {}\n"
                        + "class Svc { UserRepository repo; org.springframework.transaction.support.TransactionTemplate tx;"
                        + " void broken(User u) { tx.executeWithoutResult(status -> repo.save(u));"
                        + " repo.deleteById(1L); } }"),
                "repository write operations"));
    }

    public void testHelperCalledInsideAndOutsideTransaction_onlyUnsafePathFlagged() {
        assertEquals(1, warnings(highlight(
                "interface UserRepository extends JpaRepository<User, Long> {}\n"
                        + "class Svc { UserRepository repo; org.springframework.transaction.support.TransactionTemplate tx;"
                        + " void safe() { tx.executeWithoutResult(status -> deleteRow()); }"
                        + " void unsafe() { deleteRow(); }"
                        + " private void deleteRow() { repo.deleteById(1L); } }"),
                "private helper"));
    }

    public void testPrivateTransactionalSelfInvocation_stillFlagged() {
        assertEquals(1, warnings(highlight(
                "interface UserRepository extends JpaRepository<User, Long> {}\n"
                        + "class Svc { UserRepository repo;"
                        + " void outer() { inner(); }"
                        + " @Transactional private void inner() { repo.deleteById(1L); } }"),
                "private helper"));
    }

    public void testUnrelatedExecuteWithoutResult_doesNotCreateTransaction() {
        assertEquals(1, warnings(highlight(
                "interface UserRepository extends JpaRepository<User, Long> {}\n"
                        + "interface Work { void run(Object status); }\n"
                        + "class Executor { void executeWithoutResult(Work work) { work.run(null); } }\n"
                        + "class Svc { UserRepository repo; Executor executor;"
                        + " void broken() { executor.executeWithoutResult(status -> repo.deleteById(1L)); } }"),
                "repository write operations"));
    }


    public void testEffectivelyFinalStoredCallback_isAnalyzedAtTransactionUse() {
        assertEquals(0, warnings(highlight(
                "interface UserRepository extends JpaRepository<User, Long> {}\n"
                        + "class Svc { UserRepository repo; org.springframework.transaction.support.TransactionTemplate tx;"
                        + " void save(User u) { java.util.function.Consumer<org.springframework.transaction.TransactionStatus> work ="
                        + " status -> repo.save(u); tx.executeWithoutResult(work); } }"), "repository write operations"));
    }

    public void testReassignedStoredCallback_remainsWarningProducing() {
        assertEquals(1, warnings(highlight(
                "interface UserRepository extends JpaRepository<User, Long> {}\n"
                        + "class Svc { UserRepository repo; org.springframework.transaction.support.TransactionTemplate tx;"
                        + " void save(User u, boolean other) { java.util.function.Consumer<org.springframework.transaction.TransactionStatus> work ="
                        + " status -> repo.save(u); if (other) work = status -> repo.delete(u);"
                        + " tx.executeWithoutResult(work); } }"), "repository write operations"));
    }

    public void testCallbackFactory_isAnalyzedAtTransactionUse() {
        assertEquals(0, warnings(highlight(
                "interface UserRepository extends JpaRepository<User, Long> {}\n"
                        + "class Svc { UserRepository repo; org.springframework.transaction.support.TransactionTemplate tx;"
                        + " void run() { tx.executeWithoutResult(work()); }"
                        + " private java.util.function.Consumer<org.springframework.transaction.TransactionStatus> work() {"
                        + " return status -> repo.deleteById(1L); } }"), "private helper"));
    }

    public void testPlatformTransactionManagerStraightLineRegion_notFlagged() {
        assertEquals(0, warnings(highlight(
                "interface UserRepository extends JpaRepository<User, Long> {}\n"
                        + "class Svc { UserRepository repo; org.springframework.transaction.PlatformTransactionManager tm;"
                        + " org.springframework.transaction.TransactionDefinition def; void run() {"
                        + " org.springframework.transaction.TransactionStatus status = tm.getTransaction(def);"
                        + " repo.deleteById(1L); tm.commit(status); } }"), "repository write operations"));
    }

    public void testPlatformTransactionManagerTryRollbackRegion_notFlagged() {
        assertEquals(0, warnings(highlight(
                "interface UserRepository extends JpaRepository<User, Long> {}\n"
                        + "class Svc { UserRepository repo; org.springframework.transaction.PlatformTransactionManager tm;"
                        + " org.springframework.transaction.TransactionDefinition def; void run() {"
                        + " org.springframework.transaction.TransactionStatus status = tm.getTransaction(def);"
                        + " try { repo.deleteById(1L); tm.commit(status); }"
                        + " catch (RuntimeException ex) { tm.rollback(status); throw ex; } } }"), "repository write operations"));
    }

    public void testPlatformTransactionManagerMissingCompletion_stillFlagged() {
        assertEquals(1, warnings(highlight(
                "interface UserRepository extends JpaRepository<User, Long> {}\n"
                        + "class Svc { UserRepository repo; org.springframework.transaction.PlatformTransactionManager tm;"
                        + " org.springframework.transaction.TransactionDefinition def; void run() {"
                        + " org.springframework.transaction.TransactionStatus status = tm.getTransaction(def);"
                + " repo.deleteById(1L); } }"), "repository write operations"));
    }

    public void testPlatformTransactionManagerNestedStatus_isAmbiguousAndFlagged() {
        assertEquals(1, warnings(highlight(
                "interface UserRepository extends JpaRepository<User, Long> {}\n"
                        + "class Svc { UserRepository repo; org.springframework.transaction.PlatformTransactionManager tm;"
                        + " org.springframework.transaction.TransactionDefinition def; void run() {"
                        + " org.springframework.transaction.TransactionStatus first = tm.getTransaction(def);"
                        + " org.springframework.transaction.TransactionStatus second = tm.getTransaction(def);"
                        + " repo.deleteById(1L); tm.commit(first); } }"), "repository write operations"));
    }

    public void testReactiveExecuteAndTransactionalPublisher_notFlagged() {
        assertEquals(0, warnings(highlight(
                "interface UserRepository extends JpaRepository<User, Long> { reactor.core.publisher.Mono<User> store(User u); }\n"
                        + "class Svc { UserRepository repo; org.springframework.transaction.reactive.TransactionalOperator operator;"
                        + " org.reactivestreams.Publisher<User> run(User u) {"
                        + " return operator.execute(status -> { repo.save(u); return new reactor.core.publisher.Mono<User>(); }); }"
                        + " org.reactivestreams.Publisher<User> other(User u) {"
                        + " return operator.transactional(repo.store(u)); } }"),
                "repository write operations"));
    }

    public void testReactiveAsMethodReference_notFlagged() {
        assertEquals(0, warnings(highlight(
                "interface ReactiveRepository extends JpaRepository<User, Long> { reactor.core.publisher.Mono<User> store(User u); }\n"
                        + "class Svc { ReactiveRepository repo; org.springframework.transaction.reactive.TransactionalOperator operator;"
                        + " Object run(User u) { return repo.store(u).as(operator::transactional); } }"),
                "repository write operations"));
    }

    public void testStoredReactivePublisher_isAnalyzedAtWrappingOrReturn() {
        assertEquals(0, warnings(highlight(
                "interface ReactiveRepository extends JpaRepository<User, Long> { reactor.core.publisher.Mono<User> store(User u); }\n"
                        + "class Svc { ReactiveRepository repo; org.springframework.transaction.reactive.TransactionalOperator operator;"
                        + " org.reactivestreams.Publisher<User> safe(User u) { reactor.core.publisher.Mono<User> work = repo.store(u);"
                        + " return operator.transactional(work); } }"), "repository write operations"));
        assertEquals(1, warnings(highlight(
                "interface ReactiveRepository extends JpaRepository<User, Long> { reactor.core.publisher.Mono<User> store(User u); }\n"
                        + "class Svc { ReactiveRepository repo; reactor.core.publisher.Mono<User> unsafe(User u) {"
                        + " reactor.core.publisher.Mono<User> work = repo.store(u); return work; } }"),
                "repository write operations"));
    }

    public void testPublicAndCrossClassProjectHelpers_areTraversed() {
        List<HighlightInfo> infos = highlight(
                "interface UserRepository extends JpaRepository<User, Long> {}\n"
                        + "class Writer { UserRepository repo; public void write(User u) { repo.save(u); } }\n"
                        + "class Svc { Writer writer; void run(User u) { writer.write(u); } }");
        assertEquals(1, warnings(infos, "private helper"));
        assertEquals(1, warnings(infos, "repository write operations"));
    }

    public void testQualifiedTransactionalProjectHelper_isProxyBoundary() {
        assertEquals(0, warnings(highlight(
                "interface UserRepository extends JpaRepository<User, Long> {}\n"
                        + "class Writer { UserRepository repo; @Transactional public void write(User u) { repo.save(u); } }\n"
                + "class Svc { Writer writer; void run(User u) { writer.write(u); } }"), "private helper"));
    }

    public void testDirectlyConstructedTransactionalHelper_isNotAssumedToBeProxied() {
        assertEquals(1, warnings(highlight(
                "interface UserRepository extends JpaRepository<User, Long> {}\n"
                        + "class Writer { UserRepository repo; @Transactional public void write(User u) { repo.save(u); } }\n"
                        + "class Svc { void run(User u) { new Writer().write(u); } }"), "private helper"));
    }

    public void testSameClassTransactionalSelfInvocation_isNotBoundary() {
        assertEquals(1, warnings(highlight(
                "interface UserRepository extends JpaRepository<User, Long> {}\n"
                        + "class Svc { UserRepository repo; void run(User u) { write(u); }"
                        + " @Transactional public void write(User u) { repo.save(u); } }"), "private helper"));
    }

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
