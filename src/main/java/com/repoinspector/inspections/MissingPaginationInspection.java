package com.repoinspector.inspections;

import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.codeInspection.options.OptPane;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypeElement;
import com.intellij.psi.search.GlobalSearchScope;
import com.repoinspector.constants.SpringAnnotations;
import com.repoinspector.inspections.detector.PaginationSignatureAnalyzer;
import com.repoinspector.inspections.detector.QueryStringAnalyzer;
import com.repoinspector.inspections.fix.ConvertToPageFix;
import org.jetbrains.annotations.NotNull;

import static com.intellij.codeInspection.options.OptPane.checkbox;
import static com.intellij.codeInspection.options.OptPane.pane;

/**
 * Flags Spring Data repository methods that return an unbounded collection
 * ({@code List}/{@code Set}/{@code Collection}/{@code Iterable}/{@code Stream})
 * without any pagination, which can load an entire table into memory.
 *
 * <p>Suppressed when the method already takes a {@code Pageable}/{@code Sort},
 * returns {@code Page}/{@code Slice}, is name-limited ({@code findFirst}/{@code findTop}),
 * or its {@code @Query} contains a {@code LIMIT}.
 */
public class MissingPaginationInspection extends RepoBuddyLocalInspection {

    @SuppressWarnings("WeakerAccess") public boolean onlyWarnForFindAll = true;

    @SuppressWarnings("unused") public MissingPaginationInspection() {}
    public MissingPaginationInspection(boolean alwaysAnalyze) { super(alwaysAnalyze); }

    @Override
    public @NotNull OptPane getOptionsPane() {
        return pane(
                checkbox("onlyWarnForFindAll",
                        "Only warn for \"find all\"-style methods (name contains an 'All' segment)")
        );
    }

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        if (!shouldAnalyze()) return PsiElementVisitor.EMPTY_VISITOR;
        return new JavaElementVisitor() {
            @Override
            public void visitMethod(@NotNull PsiMethod method) {
                PsiClass containingClass = method.getContainingClass();
                if (containingClass == null || !containingClass.isInterface()) return;
                if (method.getBody() != null) return; // skip default methods

                if (!isSpringDataRepository(containingClass)) return;

                PsiType returnType = method.getReturnType();
                if (!(returnType instanceof PsiClassType classType)) return;
                PsiClass returnClass = classType.resolve();
                if (returnClass == null) return;
                if (!PaginationSignatureAnalyzer.isUnboundedCollection(returnClass.getQualifiedName())) return;

                String name = method.getName();
                if (PaginationSignatureAnalyzer.isLimitedByName(name)) return;
                if (onlyWarnForFindAll && !PaginationSignatureAnalyzer.looksLikeFindAll(name)) return;
                if (hasPageableOrSort(method)) return;
                if (queryHasLimit(method)) return;

                PsiTypeElement returnTypeElement = method.getReturnTypeElement();
                if (returnTypeElement == null) return;

                holder.registerProblem(returnTypeElement,
                        "Repository method '" + name + "' returns " + returnClass.getName()
                                + " without pagination. For large result sets this risks excessive memory "
                                + "and slow queries — return Page<T> or add a Pageable parameter.",
                        ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                        new ConvertToPageFix(method));
            }
        };
    }

    private static boolean isSpringDataRepository(@NotNull PsiClass cls) {
        if (cls.hasAnnotation(SpringAnnotations.REPOSITORY)) return true;
        JavaPsiFacade facade = JavaPsiFacade.getInstance(cls.getProject());
        GlobalSearchScope scope = GlobalSearchScope.allScope(cls.getProject());
        for (String fqn : SpringAnnotations.SPRING_DATA_BASE_TYPES) {
            PsiClass base = facade.findClass(fqn, scope);
            if (base != null && cls.isInheritor(base, true)) return true;
        }
        return false;
    }

    private static boolean hasPageableOrSort(@NotNull PsiMethod method) {
        for (PsiParameter p : method.getParameterList().getParameters()) {
            String type = p.getType().getCanonicalText();
            if (type.startsWith(SpringAnnotations.PAGEABLE) || type.startsWith(SpringAnnotations.SORT)) {
                return true;
            }
        }
        return false;
    }

    private static boolean queryHasLimit(@NotNull PsiMethod method) {
        PsiAnnotation query = method.getAnnotation(SpringAnnotations.QUERY);
        if (query == null) return false;
        PsiAnnotationMemberValue value = query.findAttributeValue("value");
        if (!(value instanceof PsiExpression expr)) return false;
        Object constant = JavaPsiFacade.getInstance(method.getProject())
                .getConstantEvaluationHelper().computeConstantExpression(expr);
        return constant instanceof String s && QueryStringAnalyzer.containsLimit(s);
    }
}
