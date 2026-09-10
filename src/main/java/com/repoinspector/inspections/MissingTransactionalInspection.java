package com.repoinspector.inspections;

import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.codeInspection.options.OptPane;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.repoinspector.constants.SpringAnnotations;
import com.repoinspector.inspections.detector.TransactionContextDetector;
import com.repoinspector.inspections.fix.AddTransactionalFix;
import org.jetbrains.annotations.NotNull;

import java.util.EnumSet;

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
 *       write that delegates through a bounded graph of resolvable production-source methods,
 *       preserving proven imperative and reactive transaction contexts.</li>
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
                        "Also analyze a bounded graph of helper methods in project production sources")
        );
    }

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        if (!shouldAnalyze(holder.getFile())) return PsiElementVisitor.EMPTY_VISITOR;
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

                EnumSet<TransactionContextDetector.WritePath> uncovered =
                        new TransactionContextDetector(includeRepositoryWriteCalls, analyzeCalledMethods)
                                .analyze(method);
                if (uncovered.contains(TransactionContextDetector.WritePath.DIRECT_DATA)) {
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

                if (includeRepositoryWriteCalls
                        && uncovered.contains(TransactionContextDetector.WritePath.DIRECT_REPOSITORY)) {
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

                if (analyzeCalledMethods && !isStatic
                        && uncovered.contains(TransactionContextDetector.WritePath.HELPER)) {
                    register(holder, method,
                            "Method '" + method.getName() + "' performs a database write through a private helper "
                                    + "or project-source helper but is not @Transactional. "
                                    + "Annotate this entry point to wrap the writes in one transaction.");
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

}
