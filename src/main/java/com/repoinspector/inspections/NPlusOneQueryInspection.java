package com.repoinspector.inspections;

import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.codeInspection.options.OptPane;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.JavaRecursiveElementVisitor;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiExpressionList;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiForeachStatement;
import com.intellij.psi.PsiLambdaExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiModifierListOwner;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiStatement;
import com.intellij.psi.PsiType;
import com.repoinspector.constants.JpaAnnotations;
import com.repoinspector.inspections.detector.LazyAssociationRules;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

import static com.intellij.codeInspection.options.OptPane.number;
import static com.intellij.codeInspection.options.OptPane.pane;

/**
 * Detects the classic N+1 query pattern: accessing a lazily-loaded JPA
 * association on a loop variable inside a {@code for-each} loop, which issues a
 * separate {@code SELECT} per iteration.
 *
 * <p>Conservative by design — it only fires when the loop variable is an
 * {@code @Entity} and the accessed property is a clearly lazy association
 * ({@code @OneToMany}/{@code @ManyToMany}, or {@code @ManyToOne}/{@code @OneToOne}
 * declared {@code fetch = LAZY}). Associations with a sufficiently large
 * {@code @BatchSize} are ignored. Reported as a warning without an automated fix,
 * since the correct remedy (a {@code JOIN FETCH} or {@code @EntityGraph}) lives in
 * the loading query.
 */
public class NPlusOneQueryInspection extends AbstractBaseJavaLocalInspectionTool {

    @SuppressWarnings("WeakerAccess") public int batchSizeThreshold = 1;

    /** Stream / collection methods whose lambda iterates per element. */
    private static final Set<String> ITERATION_METHODS = Set.of(
            "forEach", "map", "filter", "flatMap", "peek",
            "anyMatch", "allMatch", "noneMatch", "removeIf",
            "mapToInt", "mapToLong", "mapToDouble", "mapToObj"
    );

    private record Association(String simpleName, String fetch) {}

    @Override
    public @NotNull OptPane getOptionsPane() {
        return pane(
                number("batchSizeThreshold",
                        "Ignore associations whose @BatchSize exceeds:", 0, 100_000)
        );
    }

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new JavaElementVisitor() {
            @Override
            public void visitForeachStatement(@NotNull PsiForeachStatement statement) {
                PsiParameter loopVar = statement.getIterationParameter();
                PsiClass entityClass = resolveClass(loopVar.getType());
                if (entityClass == null || !isEntity(entityClass)) return;
                PsiStatement body = statement.getBody();
                if (body != null) scanForLazyAccess(body, loopVar, entityClass, holder);
            }

            @Override
            public void visitLambdaExpression(@NotNull PsiLambdaExpression lambda) {
                PsiParameter[] params = lambda.getParameterList().getParameters();
                if (params.length != 1 || !isIterationLambda(lambda)) return;
                PsiParameter loopVar = params[0];
                PsiClass entityClass = resolveClass(loopVar.getType());
                if (entityClass == null || !isEntity(entityClass)) return;
                PsiElement body = lambda.getBody();
                if (body != null) scanForLazyAccess(body, loopVar, entityClass, holder);
            }
        };
    }

    private void scanForLazyAccess(@NotNull PsiElement body, @NotNull PsiParameter loopVar,
                                   @NotNull PsiClass entityClass, @NotNull ProblemsHolder holder) {
        body.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitMethodCallExpression(@NotNull PsiMethodCallExpression call) {
                super.visitMethodCallExpression(call);

                PsiReferenceExpression methodExpr = call.getMethodExpression();
                if (!(methodExpr.getQualifierExpression() instanceof PsiReferenceExpression qualifier)) return;
                if (!qualifier.isReferenceTo(loopVar)) return;

                String property = lazyAssociationProperty(call, methodExpr.getReferenceName(), entityClass);
                if (property == null) return;

                holder.registerProblem(call,
                        "Potential N+1 query: accessing lazy association '" + property
                                + "' inside a loop issues a separate SELECT per iteration. Use JOIN FETCH "
                                + "or @EntityGraph when loading " + entityClass.getName() + ".",
                        ProblemHighlightType.GENERIC_ERROR_OR_WARNING);
            }
        });
    }

    /** True if the lambda is the argument of a stream/collection iteration call. */
    private static boolean isIterationLambda(@NotNull PsiLambdaExpression lambda) {
        if (!(lambda.getParent() instanceof PsiExpressionList args)) return false;
        if (!(args.getParent() instanceof PsiMethodCallExpression call)) return false;
        return ITERATION_METHODS.contains(call.getMethodExpression().getReferenceName());
    }

    private @Nullable String lazyAssociationProperty(@NotNull PsiMethodCallExpression call,
                                                     @Nullable String getterName,
                                                     @NotNull PsiClass entityClass) {
        PsiMethod resolved = call.resolveMethod();
        String property = LazyAssociationRules.propertyNameFromGetter(getterName);
        PsiField field = property != null ? entityClass.findFieldByName(property, true) : null;

        Association association = associationOf(resolved);
        if (association == null) association = associationOf(field);
        if (association == null) return null;
        if (!LazyAssociationRules.isLazyAssociation(association.simpleName(), association.fetch())) return null;
        if (suppressedByBatchSize(field) || suppressedByBatchSize(resolved)) return null;

        return property != null ? property : association.simpleName();
    }

    private static @Nullable Association associationOf(@Nullable PsiModifierListOwner owner) {
        if (owner == null) return null;
        for (String fqn : JpaAnnotations.ASSOCIATION_FQNS) {
            PsiAnnotation annotation = owner.getAnnotation(fqn);
            if (annotation == null) continue;
            return new Association(JpaAnnotations.simpleName(fqn), fetchValue(annotation));
        }
        return null;
    }

    private static @Nullable String fetchValue(@NotNull PsiAnnotation association) {
        PsiAnnotationMemberValue fetch = association.findDeclaredAttributeValue("fetch");
        return fetch instanceof PsiReferenceExpression ref ? ref.getReferenceName() : null;
    }

    private boolean suppressedByBatchSize(@Nullable PsiModifierListOwner owner) {
        if (owner == null) return false;
        PsiAnnotation batchSize = owner.getAnnotation(JpaAnnotations.BATCH_SIZE);
        if (batchSize == null) return false;
        Integer size = intAttribute(batchSize, "size");
        return size == null || size > batchSizeThreshold;
    }

    private static @Nullable Integer intAttribute(@NotNull PsiAnnotation annotation, @NotNull String name) {
        PsiAnnotationMemberValue value = annotation.findDeclaredAttributeValue(name);
        if (!(value instanceof PsiExpression expr)) return null;
        Object constant = JavaPsiFacade.getInstance(annotation.getProject())
                .getConstantEvaluationHelper().computeConstantExpression(expr);
        return constant instanceof Integer i ? i : null;
    }

    private static @Nullable PsiClass resolveClass(@Nullable PsiType type) {
        return type instanceof PsiClassType classType ? classType.resolve() : null;
    }

    private static boolean isEntity(@NotNull PsiClass cls) {
        for (String fqn : JpaAnnotations.ENTITY_FQNS) {
            if (cls.hasAnnotation(fqn)) return true;
        }
        return false;
    }
}
