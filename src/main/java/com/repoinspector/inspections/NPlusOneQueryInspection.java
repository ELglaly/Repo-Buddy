package com.repoinspector.inspections;

import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.codeInspection.options.OptPane;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.JavaRecursiveElementVisitor;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiArrayAccessExpression;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassInitializer;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiExpressionList;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiForeachStatement;
import com.intellij.psi.PsiLambdaExpression;
import com.intellij.psi.PsiLoopStatement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiMethodReferenceExpression;
import com.intellij.psi.PsiModifierListOwner;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiStatement;
import com.intellij.psi.PsiType;
import com.repoinspector.constants.JpaAnnotations;
import com.repoinspector.inspections.detector.DataAccessCalls;
import com.repoinspector.inspections.detector.LazyAssociationRules;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

import static com.intellij.codeInspection.options.OptPane.checkbox;
import static com.intellij.codeInspection.options.OptPane.number;
import static com.intellij.codeInspection.options.OptPane.pane;

/**
 * Detects N+1 query patterns in two complementary ways:
 *
 * <ol>
 *   <li><b>Lazy association in a loop</b> — accessing a lazily-loaded JPA association on a
 *       {@code for-each} (or stream-iteration) loop variable, which issues a separate
 *       {@code SELECT} per iteration.</li>
 *   <li><b>Query call in a loop</b> — invoking a Spring Data repository method, a JPA
 *       {@code EntityManager}/{@code Query} read, a Hibernate {@code Session} read, or a
 *       {@code JdbcTemplate} query inside any loop ({@code for}/{@code for-each}/{@code while}/
 *       {@code do-while}) or stream-iteration lambda / method reference. This is the classic
 *       {@code repository.findById(x)}-inside-a-loop anti-pattern.</li>
 * </ol>
 *
 * <p>Both are conservative: lazy-association detection only fires for clearly lazy
 * associations on an {@code @Entity} loop variable, and query-in-loop detection requires the
 * call to resolve to a recognised data-access API and to sit in the loop <em>body</em> (not
 * its header, e.g. {@code for (X x : repo.findAll())} is not flagged).
 */
public class NPlusOneQueryInspection extends RepoBuddyLocalInspection {

    @SuppressWarnings("WeakerAccess") public int batchSizeThreshold = 1;
    @SuppressWarnings("WeakerAccess") public boolean reportQueryCallsInLoops = true;

    @SuppressWarnings("unused") public NPlusOneQueryInspection() {}
    public NPlusOneQueryInspection(boolean alwaysAnalyze) { super(alwaysAnalyze); }

    /** Stream / collection methods whose lambda iterates per element. */
    private static final Set<String> ITERATION_METHODS = Set.of(
            "forEach", "forEachOrdered", "map", "filter", "flatMap", "peek",
            "anyMatch", "allMatch", "noneMatch", "removeIf",
            "mapToInt", "mapToLong", "mapToDouble", "mapToObj"
    );

    private record Association(String simpleName, String fetch) {}

    @Override
    public @NotNull OptPane getOptionsPane() {
        return pane(
                number("batchSizeThreshold",
                        "Ignore associations whose @BatchSize exceeds:", 0, 100_000),
                checkbox("reportQueryCallsInLoops",
                        "Flag repository / EntityManager / JDBC query calls executed inside a loop")
        );
    }

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        if (!shouldAnalyze()) return PsiElementVisitor.EMPTY_VISITOR;
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

            @Override
            public void visitMethodCallExpression(@NotNull PsiMethodCallExpression call) {
                if (reportQueryCallsInLoops && DataAccessCalls.isQueryCall(call) && isInsideLoop(call)) {
                    registerQueryInLoop(call, call.getMethodExpression().getReferenceName(), holder);
                    return;
                }
                checkLazyElementAccess(call, holder);
            }

            @Override
            public void visitMethodReferenceExpression(@NotNull PsiMethodReferenceExpression ref) {
                if (!reportQueryCallsInLoops) return;
                if (!(ref.resolve() instanceof PsiMethod method)) return;
                if (!DataAccessCalls.isQueryMethod(method)) return;
                if (!isIterationMethodArgument(ref)) return;
                registerQueryInLoop(ref, ref.getReferenceName(), holder);
            }
        };
    }

    private static void registerQueryInLoop(@NotNull PsiElement anchor, @Nullable String methodName,
                                            @NotNull ProblemsHolder holder) {
        String label = methodName != null ? "'" + methodName + "'" : "method";
        holder.registerProblem(anchor,
                "Potential N+1 query: data-access call " + label + " runs once per loop iteration. "
                        + "Fetch all rows in a single batch query before the loop "
                        + "(e.g. findAllById(ids) or a JOIN FETCH).",
                ProblemHighlightType.GENERIC_ERROR_OR_WARNING);
    }

    /**
     * True if {@code element} executes inside the body of a loop within the same method.
     * Stops at method / class / non-iteration-lambda boundaries, and ignores loop headers
     * (the iterated value or condition) by requiring the path to descend through the body.
     */
    private boolean isInsideLoop(@NotNull PsiElement element) {
        PsiElement prev = element;
        PsiElement cur = element.getParent();
        while (cur != null) {
            if (cur instanceof PsiLoopStatement loop) {
                return loop.getBody() == prev;
            }
            if (cur instanceof PsiLambdaExpression lambda) {
                // A stream-iteration lambda *is* the loop; any other lambda is a separate
                // (possibly deferred) execution context, so stop there to avoid false positives.
                return isIterationLambda(lambda);
            }
            if (cur instanceof PsiMethod || cur instanceof PsiClass || cur instanceof PsiClassInitializer) {
                return false;
            }
            prev = cur;
            cur = cur.getParent();
        }
        return false;
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
                registerLazy(call, property, entityClass, holder);
            }
        });
    }

    /**
     * Catches the same lazy-association N+1 in indexed {@code for}/{@code while} loops, where the
     * entity is reached through a per-iteration element access ({@code list.get(i)},
     * {@code iterator.next()}, or {@code array[i]}) rather than a {@code for-each} variable.
     */
    private void checkLazyElementAccess(@NotNull PsiMethodCallExpression call, @NotNull ProblemsHolder holder) {
        PsiExpression qualifier = call.getMethodExpression().getQualifierExpression();
        if (!isPerIterationElementAccess(qualifier) || !isInsideLoop(call)) return;
        PsiClass entityClass = resolveClass(qualifier.getType());
        if (entityClass == null || !isEntity(entityClass)) return;
        String property = lazyAssociationProperty(call, call.getMethodExpression().getReferenceName(), entityClass);
        if (property == null) return;
        registerLazy(call, property, entityClass, holder);
    }

    /** True for {@code list.get(i)} / {@code iterator.next()} / {@code array[i]} element access. */
    private static boolean isPerIterationElementAccess(@Nullable PsiExpression qualifier) {
        if (qualifier instanceof PsiArrayAccessExpression) return true;
        if (qualifier instanceof PsiMethodCallExpression mc) {
            String name = mc.getMethodExpression().getReferenceName();
            return "get".equals(name) || "next".equals(name);
        }
        return false;
    }

    private static void registerLazy(@NotNull PsiElement anchor, @NotNull String property,
                                     @NotNull PsiClass entityClass, @NotNull ProblemsHolder holder) {
        holder.registerProblem(anchor,
                "Potential N+1 query: accessing lazy association '" + property
                        + "' inside a loop issues a separate SELECT per iteration. Use JOIN FETCH "
                        + "or @EntityGraph when loading " + entityClass.getName() + ".",
                ProblemHighlightType.GENERIC_ERROR_OR_WARNING);
    }

    /** True if the lambda is the argument of a stream/collection iteration call. */
    private static boolean isIterationLambda(@NotNull PsiLambdaExpression lambda) {
        return isIterationMethodArgument(lambda);
    }

    /** True if {@code expr} is an argument to a stream/collection iteration method. */
    private static boolean isIterationMethodArgument(@NotNull PsiExpression expr) {
        if (!(expr.getParent() instanceof PsiExpressionList args)) return false;
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
