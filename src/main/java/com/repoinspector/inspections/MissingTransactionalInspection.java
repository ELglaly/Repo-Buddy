package com.repoinspector.inspections;

import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.codeInspection.options.OptPane;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.JavaRecursiveElementVisitor;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.search.GlobalSearchScope;
import com.repoinspector.constants.SpringAnnotations;
import com.repoinspector.inspections.detector.TransactionWriteSignals;
import com.repoinspector.inspections.fix.AddTransactionalFix;
import com.repoinspector.model.OperationType;
import org.jetbrains.annotations.NotNull;

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
 *   <li><b>EntityManager writes</b> — a method body that calls {@code persist}/{@code merge}/
 *       {@code remove}/{@code flush} or {@code Query.executeUpdate()} outside a transaction
 *       throws {@code TransactionRequiredException}.</li>
 *   <li><b>Repository write calls</b> (opt-in) — a method that calls repository
 *       {@code save}/{@code delete}/… without {@code @Transactional}; useful when several
 *       writes must be atomic.</li>
 * </ul>
 */
public class MissingTransactionalInspection extends AbstractBaseJavaLocalInspectionTool {

    @SuppressWarnings("WeakerAccess") public boolean ignorePrivateMethods = true;
    @SuppressWarnings("WeakerAccess") public boolean includeRepositoryWriteCalls = true;

    @Override
    public @NotNull OptPane getOptionsPane() {
        return pane(
                checkbox("ignorePrivateMethods",
                        "Ignore private methods (Spring's proxy cannot apply @Transactional to them)"),
                checkbox("includeRepositoryWriteCalls",
                        "Also flag methods that call repository save/delete/update without @Transactional")
        );
    }

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
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
                if (method.hasModifierProperty(PsiModifier.STATIC)) return;
                if (ignorePrivateMethods && method.hasModifierProperty(PsiModifier.PRIVATE)) return;

                if (bodyHasJpaWrite(body)) {
                    register(holder, method,
                            "Method '" + method.getName() + "' performs a JPA write (persist/merge/remove/"
                                    + "flush/executeUpdate) without @Transactional, risking "
                                    + "TransactionRequiredException.");
                    return;
                }

                if (includeRepositoryWriteCalls && bodyHasRepositoryWrite(body)) {
                    register(holder, method,
                            "Method '" + method.getName() + "' calls repository write operations without "
                                    + "@Transactional; wrap it in a transaction to keep the writes atomic.");
                }
            }
        };
    }

    private static void register(@NotNull ProblemsHolder holder, @NotNull PsiMethod method,
                                 @NotNull String message) {
        PsiIdentifier nameId = method.getNameIdentifier();
        PsiElement anchor = nameId != null ? nameId : method;
        holder.registerProblem(anchor, message, ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                new AddTransactionalFix(method));
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

    private static boolean bodyHasJpaWrite(@NotNull PsiCodeBlock body) {
        boolean[] found = {false};
        body.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitMethodCallExpression(@NotNull PsiMethodCallExpression call) {
                super.visitMethodCallExpression(call);
                if (found[0]) return;
                String name = call.getMethodExpression().getReferenceName();
                if (!TransactionWriteSignals.isJpaWriteMethod(name)) return;
                PsiMethod resolved = call.resolveMethod();
                if (resolved == null) return;
                PsiClass owner = resolved.getContainingClass();
                if (owner != null && TransactionWriteSignals.isJpaPersistenceType(owner.getQualifiedName())) {
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
                if (owner == null || !isSpringDataRepository(owner)) return;
                if (resolved.hasAnnotation(SpringAnnotations.MODIFYING)
                        || OperationType.fromMethodName(name) == OperationType.WRITE) {
                    found[0] = true;
                }
            }
        });
        return found[0];
    }

    private static boolean isSpringDataRepository(@NotNull PsiClass cls) {
        if (cls.hasAnnotation(SpringAnnotations.REPOSITORY)) return true;
        JavaPsiFacade facade = JavaPsiFacade.getInstance(cls.getProject());
        GlobalSearchScope scope = GlobalSearchScope.allScope(cls.getProject());
        for (String fqn : SpringAnnotations.SPRING_DATA_BASE_TYPES) {
            PsiClass base = facade.findClass(fqn, scope);
            if (base != null && (cls.equals(base) || cls.isInheritor(base, true))) return true;
        }
        return false;
    }
}
