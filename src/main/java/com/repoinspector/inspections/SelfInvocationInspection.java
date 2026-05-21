package com.repoinspector.inspections;

import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiThisExpression;
import com.intellij.psi.util.PsiTreeUtil;
import com.repoinspector.constants.SpringAnnotations;
import org.jetbrains.annotations.NotNull;

/**
 * Flags Spring's <em>self-invocation</em> pitfall: calling a {@code @Transactional} method
 * from another method of the same class through {@code this} (or with no qualifier). Because
 * Spring's transaction support is proxy-based, the call goes straight to the target instance
 * and bypasses the proxy, so the method's transactional settings (propagation, isolation,
 * rollback rules, {@code REQUIRES_NEW}, …) silently do not apply.
 *
 * <p>Only method-level {@code @Transactional} callees are reported; a call routed through a
 * different bean reference (e.g. an injected self-reference) is not flagged because that path
 * goes through the proxy.
 */
public class SelfInvocationInspection extends RepoBuddyLocalInspection {

    @SuppressWarnings("unused") public SelfInvocationInspection() {}
    public SelfInvocationInspection(boolean alwaysAnalyze) { super(alwaysAnalyze); }

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        if (!shouldAnalyze()) return PsiElementVisitor.EMPTY_VISITOR;
        return new JavaElementVisitor() {
            @Override
            public void visitMethodCallExpression(@NotNull PsiMethodCallExpression call) {
                if (!isUnqualifiedOrThis(call.getMethodExpression().getQualifierExpression())) return;

                PsiMethod callee = call.resolveMethod();
                if (callee == null || callee.hasModifierProperty(PsiModifier.STATIC)) return;
                if (!hasMethodLevelTransactional(callee)) return;

                PsiClass callerClass = PsiTreeUtil.getParentOfType(call, PsiClass.class);
                if (callerClass == null || !callerClass.equals(callee.getContainingClass())) return;

                holder.registerProblem(call.getMethodExpression(),
                        "Self-invocation of @Transactional method '" + callee.getName()
                                + "' bypasses Spring's proxy — its transactional settings will not apply. "
                                + "Call it through an injected self-reference or move it to another bean.",
                        ProblemHighlightType.GENERIC_ERROR_OR_WARNING);
            }
        };
    }

    /** True for {@code foo()} (no qualifier) or {@code this.foo()}; false for {@code other.foo()}/{@code super.foo()}. */
    private static boolean isUnqualifiedOrThis(PsiExpression qualifier) {
        if (qualifier == null) return true;
        if (!(qualifier instanceof PsiThisExpression thisExpr)) return false;
        // A qualified this (Outer.this) targets an enclosing instance, not necessarily self — ignore.
        return thisExpr.getQualifier() == null;
    }

    private static boolean hasMethodLevelTransactional(@NotNull PsiMethod method) {
        for (String fqn : SpringAnnotations.TRANSACTIONAL_FQNS) {
            if (method.hasAnnotation(fqn)) return true;
        }
        return false;
    }
}
