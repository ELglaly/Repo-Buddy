package com.repoinspector.inspections;

import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.codeInspection.options.OptPane;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.JavaRecursiveElementVisitor;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiModifier;
import com.repoinspector.constants.SpringAnnotations;
import com.repoinspector.inspections.detector.DataAccessCalls;
import com.repoinspector.inspections.detector.TransactionWriteSignals;
import com.repoinspector.inspections.fix.AddTransactionalFix;
import com.repoinspector.model.OperationType;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.Set;

import static com.intellij.codeInspection.options.OptPane.checkbox;
import static com.intellij.codeInspection.options.OptPane.pane;

/**
 * Flags methods that perform database writes without transactional context.
 *
 * <ul>
 *   <li><b>@Modifying without @Transactional</b> — a Spring Data {@code @Modifying}
 *       query method that is neither annotated {@code @Transactional} nor declared in a
 *       {@code @Transactional} class will throw at runtime unless every caller supplies
 *       a transaction.</li>
 *   <li><b>JPA / Hibernate / JDBC writes</b> — a method body that calls JPA
 *       {@code persist}/{@code merge}/{@code remove}/{@code flush}/{@code executeUpdate},
 *       a Hibernate {@code Session} mutator ({@code save}/{@code update}/…), or a
 *       {@code JdbcTemplate} {@code update}/{@code execute} outside a transaction.</li>
 *   <li><b>Repository write calls</b> (opt-in) — a method that calls repository
 *       {@code save}/{@code delete}/… without {@code @Transactional}.</li>
 *   <li><b>Transitive writes through private helpers</b> (opt-in) — a method with no direct
 *       write that delegates to a private same-class method which writes; Spring cannot make
 *       the private helper transactional, so the public entry point must be annotated.</li>
 * </ul>
 *
 * <p>Static methods that write are reported with a distinct message (and no quick fix)
 * because Spring's proxy-based transaction management cannot apply to them.
 */
public class MissingTransactionalInspection extends RepoBuddyLocalInspection {

    @SuppressWarnings("WeakerAccess") public boolean ignorePrivateMethods = true;
    @SuppressWarnings("WeakerAccess") public boolean includeRepositoryWriteCalls = true;
    @SuppressWarnings("WeakerAccess") public boolean analyzeCalledMethods = true;

    @SuppressWarnings("unused") public MissingTransactionalInspection() {}
    public MissingTransactionalInspection(boolean alwaysAnalyze) { super(alwaysAnalyze); }

    @Override
    public @NotNull OptPane getOptionsPane() {
        return pane(
                checkbox("ignorePrivateMethods",
                        "Ignore private methods (Spring's proxy cannot apply @Transactional to them)"),
                checkbox("includeRepositoryWriteCalls",
                        "Also flag methods that call repository save/delete/update without @Transactional"),
                checkbox("analyzeCalledMethods",
                        "Also flag methods that write only through a private helper method")
        );
    }

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        if (!shouldAnalyze()) return PsiElementVisitor.EMPTY_VISITOR;
        return new JavaElementVisitor() {
            @Override
            public void visitMethod(@NotNull PsiMethod method) {
                // Check A: a @Modifying query method must be transactional.
                if (method.hasAnnotation(SpringAnnotations.MODIFYING)) {
                    if (!isTransactional(method)) {
                        register(holder, method,
                                "@Modifying query method '" + method.getName()
                                        + "' is not @Transactional. It will fail at runtime unless every "
                                        + "caller runs inside a transaction.");
                    }
                    return;
                }

                PsiCodeBlock body = method.getBody();
                if (body == null) return;
                if (isTransactional(method)) return;
                if (ignorePrivateMethods && method.hasModifierProperty(PsiModifier.PRIVATE)) return;

                boolean isStatic = method.hasModifierProperty(PsiModifier.STATIC);

                if (bodyHasDataWrite(body)) {
                    if (isStatic) {
                        registerStatic(holder, method,
                                "Static method '" + method.getName() + "' performs a database write but cannot "
                                        + "be made @Transactional — Spring cannot proxy static methods. Refactor "
                                        + "to an instance method or manage the transaction manually.");
                    } else {
                        register(holder, method,
                                "Method '" + method.getName() + "' performs a database write (JPA/Hibernate/JDBC) "
                                        + "without @Transactional, risking TransactionRequiredException.");
                    }
                    return;
                }

                if (includeRepositoryWriteCalls && bodyHasRepositoryWrite(body)) {
                    if (isStatic) {
                        registerStatic(holder, method,
                                "Static method '" + method.getName() + "' calls repository write operations but "
                                        + "cannot be made @Transactional — Spring cannot proxy static methods. "
                                        + "Refactor to an instance method or manage the transaction manually.");
                    } else {
                        register(holder, method,
                                "Method '" + method.getName() + "' calls repository write operations without "
                                        + "@Transactional; wrap it in a transaction to keep the writes atomic.");
                    }
                    return;
                }

                if (analyzeCalledMethods && !isStatic && callsPrivateWriter(method)) {
                    register(holder, method,
                            "Method '" + method.getName() + "' performs a database write through a private helper "
                                    + "but is not @Transactional. Spring cannot make the helper transactional, so "
                                    + "annotate this entry point to wrap the writes in one transaction.");
                }
            }
        };
    }

    private static void register(@NotNull ProblemsHolder holder, @NotNull PsiMethod method,
                                 @NotNull String message) {
        holder.registerProblem(anchorOf(method), message,
                ProblemHighlightType.GENERIC_ERROR_OR_WARNING, new AddTransactionalFix(method));
    }

    /** Reports without the @Transactional quick fix (annotating the element would not help). */
    private static void registerStatic(@NotNull ProblemsHolder holder, @NotNull PsiMethod method,
                                       @NotNull String message) {
        holder.registerProblem(anchorOf(method), message, ProblemHighlightType.GENERIC_ERROR_OR_WARNING);
    }

    private static @NotNull PsiElement anchorOf(@NotNull PsiMethod method) {
        PsiIdentifier nameId = method.getNameIdentifier();
        return nameId != null ? nameId : method;
    }

    private static boolean isTransactional(@NotNull PsiMethod method) {
        for (String fqn : SpringAnnotations.TRANSACTIONAL_FQNS) {
            if (method.hasAnnotation(fqn)) return true;
        }
        PsiClass cls = method.getContainingClass();
        if (cls != null) {
            for (String fqn : SpringAnnotations.TRANSACTIONAL_FQNS) {
                if (cls.hasAnnotation(fqn)) return true;
            }
        }
        return false;
    }

    /**
     * True if {@code method} performs a write only by delegating to a private same-class
     * method (transitively). Private helpers cannot be proxied, so the public caller is the
     * element that needs {@code @Transactional}.
     */
    private boolean callsPrivateWriter(@NotNull PsiMethod method) {
        PsiClass owner = method.getContainingClass();
        if (owner == null) return false;
        return scanPrivateCallees(method, owner, new HashSet<>());
    }

    private boolean scanPrivateCallees(@NotNull PsiMethod method, @NotNull PsiClass owner,
                                       @NotNull Set<PsiMethod> visited) {
        PsiCodeBlock body = method.getBody();
        if (body == null) return false;
        boolean[] found = {false};
        body.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitMethodCallExpression(@NotNull PsiMethodCallExpression call) {
                super.visitMethodCallExpression(call);
                if (found[0]) return;
                PsiMethod callee = call.resolveMethod();
                if (callee == null || !callee.hasModifierProperty(PsiModifier.PRIVATE)) return;
                if (!owner.equals(callee.getContainingClass())) return;
                if (!visited.add(callee)) return;
                PsiCodeBlock calleeBody = callee.getBody();
                if (calleeBody != null && (bodyHasDataWrite(calleeBody)
                        || (includeRepositoryWriteCalls && bodyHasRepositoryWrite(calleeBody)))) {
                    found[0] = true;
                    return;
                }
                if (scanPrivateCallees(callee, owner, visited)) found[0] = true;
            }
        });
        return found[0];
    }

    private static boolean bodyHasDataWrite(@NotNull PsiCodeBlock body) {
        boolean[] found = {false};
        body.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitMethodCallExpression(@NotNull PsiMethodCallExpression call) {
                super.visitMethodCallExpression(call);
                if (found[0]) return;
                String name = call.getMethodExpression().getReferenceName();
                if (name == null) return;
                PsiMethod resolved = call.resolveMethod();
                if (resolved == null) return;
                PsiClass owner = resolved.getContainingClass();
                if (owner == null) return;
                String fqn = owner.getQualifiedName();
                if (TransactionWriteSignals.isJpaWriteMethod(name)
                        && TransactionWriteSignals.isJpaPersistenceType(fqn)) {
                    found[0] = true;
                } else if (TransactionWriteSignals.isHibernateWriteMethod(name)
                        && TransactionWriteSignals.isHibernateSessionType(fqn)) {
                    found[0] = true;
                } else if (TransactionWriteSignals.isJdbcWriteMethod(name)
                        && TransactionWriteSignals.isJdbcTemplateType(fqn)) {
                    found[0] = true;
                }
            }
        });
        return found[0];
    }

    private static boolean bodyHasRepositoryWrite(@NotNull PsiCodeBlock body) {
        boolean[] found = {false};
        body.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitMethodCallExpression(@NotNull PsiMethodCallExpression call) {
                super.visitMethodCallExpression(call);
                if (found[0]) return;
                String name = call.getMethodExpression().getReferenceName();
                if (name == null) return;
                PsiMethod resolved = call.resolveMethod();
                if (resolved == null) return;
                PsiClass owner = resolved.getContainingClass();
                if (owner == null || !DataAccessCalls.isSpringDataRepository(owner)) return;
                if (resolved.hasAnnotation(SpringAnnotations.MODIFYING)
                        || OperationType.fromMethodName(name) == OperationType.WRITE) {
                    found[0] = true;
                }
            }
        });
        return found[0];
    }
}
