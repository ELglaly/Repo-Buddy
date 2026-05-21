package com.repoinspector.inspections;

import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.codeInspection.options.OptPane;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiPolyadicExpression;
import com.repoinspector.constants.SpringAnnotations;
import com.repoinspector.inspections.detector.QueryStringAnalyzer;
import com.repoinspector.inspections.fix.AddParamAnnotationFix;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

import static com.intellij.codeInspection.options.OptPane.checkbox;
import static com.intellij.codeInspection.options.OptPane.pane;

/**
 * Flags risky or incorrect Spring Data {@code @Query} declarations:
 *
 * <ul>
 *   <li><b>Missing {@code @Param}</b> — a named bind parameter ({@code :x}) has
 *       neither a matching {@code @Param("x")} nor a parameter whose name matches,
 *       so binding will fail at runtime unless the project is compiled with
 *       {@code -parameters}.</li>
 *   <li><b>SpEL injection surface</b> — the query embeds a SpEL expression
 *       ({@code #{...}}); these can interpolate untrusted input (e.g. dynamic
 *       {@code ORDER BY} in native queries) and warrant review.</li>
 *   <li><b>String concatenation</b> — the {@code @Query} value is assembled via
 *       {@code +} rather than a single literal (off by default; Java requires
 *       the operands to be compile-time constants, so this is a style check).</li>
 * </ul>
 */
public class UnsafeQueryInspection extends RepoBuddyLocalInspection {

    @SuppressWarnings("WeakerAccess") public boolean checkMissingParam = true;
    @SuppressWarnings("WeakerAccess") public boolean flagSpel = true;
    @SuppressWarnings("WeakerAccess") public boolean allowConcatenationForConstants = false;

    @SuppressWarnings("unused") public UnsafeQueryInspection() {}
    public UnsafeQueryInspection(boolean alwaysAnalyze) { super(alwaysAnalyze); }

    @Override
    public @NotNull OptPane getOptionsPane() {
        return pane(
                checkbox("checkMissingParam",
                        "Report named parameters that are missing a matching @Param"),
                checkbox("flagSpel",
                        "Flag SpEL (#{...}) expressions as an injection surface"),
                checkbox("allowConcatenationForConstants",
                        "Allow building the @Query value with string concatenation")
        );
    }

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        if (!shouldAnalyze()) return PsiElementVisitor.EMPTY_VISITOR;
        return new JavaElementVisitor() {
            @Override
            public void visitMethod(@NotNull PsiMethod method) {
                PsiAnnotation query = method.getAnnotation(SpringAnnotations.QUERY);
                if (query == null) return;

                PsiAnnotationMemberValue valueExpr = query.findAttributeValue("value");
                if (valueExpr == null) return;

                String queryText = constantString(method.getProject(), valueExpr);

                if (!allowConcatenationForConstants
                        && valueExpr instanceof PsiPolyadicExpression) {
                    holder.registerProblem(valueExpr,
                            "Avoid building the @Query value by string concatenation; prefer a single string literal.",
                            ProblemHighlightType.GENERIC_ERROR_OR_WARNING);
                }

                if (queryText == null || queryText.isBlank()) return;

                if (flagSpel && QueryStringAnalyzer.containsSpel(queryText)) {
                    holder.registerProblem(valueExpr,
                            "@Query uses a SpEL expression (#{...}). Ensure it never interpolates "
                                    + "untrusted input — dynamic ORDER BY or native fragments are injection-prone.",
                            ProblemHighlightType.GENERIC_ERROR_OR_WARNING);
                }

                if (checkMissingParam) {
                    reportMissingParams(holder, method, valueExpr, queryText);
                }
            }
        };
    }

    private void reportMissingParams(@NotNull ProblemsHolder holder,
                                     @NotNull PsiMethod method,
                                     @NotNull PsiAnnotationMemberValue valueExpr,
                                     @NotNull String queryText) {
        for (String named : QueryStringAnalyzer.namedParameters(queryText)) {
            if (hasMatchingParam(method, named)) continue;

            LocalQuickFix fix = buildAddParamFix(method, named);
            holder.registerProblem(valueExpr,
                    "Named parameter ':" + named + "' has no matching @Param(\"" + named
                            + "\") and no parameter named '" + named
                            + "'. Binding will fail unless compiled with -parameters.",
                    ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                    fix == null ? LocalQuickFix.EMPTY_ARRAY : new LocalQuickFix[]{fix});
        }
    }

    /** A named param is satisfied by an explicit {@code @Param("x")} or a same-named parameter. */
    private static boolean hasMatchingParam(@NotNull PsiMethod method, @NotNull String named) {
        for (PsiParameter p : method.getParameterList().getParameters()) {
            if (named.equals(p.getName())) return true;
            String declared = paramAnnotationValue(p);
            if (named.equals(declared)) return true;
        }
        return false;
    }

    /**
     * Build a fix only when there is exactly one bindable parameter lacking a
     * {@code @Param}; otherwise the target is ambiguous and we report without a fix.
     */
    private static LocalQuickFix buildAddParamFix(@NotNull PsiMethod method, @NotNull String named) {
        List<PsiParameter> candidates = new ArrayList<>();
        for (PsiParameter p : method.getParameterList().getParameters()) {
            if (paramAnnotationValue(p) != null) continue;
            if (isSpecialParam(p)) continue;
            candidates.add(p);
        }
        if (candidates.size() != 1) return null;
        return new AddParamAnnotationFix(candidates.get(0), named);
    }

    /** Spring Data structural parameters that never carry a bind value. */
    private static boolean isSpecialParam(@NotNull PsiParameter p) {
        String type = p.getType().getCanonicalText();
        return type.startsWith(SpringAnnotations.PAGEABLE) || type.startsWith(SpringAnnotations.SORT);
    }

    /** The value of a {@code @Param} on the parameter, or {@code null} if absent. */
    private static String paramAnnotationValue(@NotNull PsiParameter p) {
        PsiAnnotation ann = p.getAnnotation(SpringAnnotations.PARAM);
        if (ann == null) return null;
        PsiAnnotationMemberValue v = ann.findAttributeValue("value");
        if (v == null) return null;
        Object constant = JavaPsiFacade.getInstance(p.getProject())
                .getConstantEvaluationHelper().computeConstantExpression(v);
        return constant instanceof String s ? s : null;
    }

    private static String constantString(@NotNull Project project, @NotNull PsiAnnotationMemberValue value) {
        if (!(value instanceof PsiExpression expr)) return null;
        Object constant = JavaPsiFacade.getInstance(project)
                .getConstantEvaluationHelper().computeConstantExpression(expr);
        return constant instanceof String s ? s : null;
    }
}
