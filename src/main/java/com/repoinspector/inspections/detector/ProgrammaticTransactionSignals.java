package com.repoinspector.inspections.detector;

import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiMethodReferenceExpression;
import com.intellij.psi.PsiType;
import com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

/** Semantic recognition of Spring programmatic transaction callback boundaries. */
public final class ProgrammaticTransactionSignals {

    public static final String TRANSACTION_TEMPLATE =
            "org.springframework.transaction.support.TransactionTemplate";
    public static final String TRANSACTION_OPERATIONS =
            "org.springframework.transaction.support.TransactionOperations";
    public static final String PLATFORM_TRANSACTION_MANAGER =
            "org.springframework.transaction.PlatformTransactionManager";
    public static final String TRANSACTION_STATUS =
            "org.springframework.transaction.TransactionStatus";
    public static final String TRANSACTIONAL_OPERATOR =
            "org.springframework.transaction.reactive.TransactionalOperator";
    public static final String REACTIVE_STREAMS_PUBLISHER = "org.reactivestreams.Publisher";

    private static final Set<String> CALLBACK_METHODS = Set.of("execute", "executeWithoutResult");

    private ProgrammaticTransactionSignals() {}

    /** True only when the call resolves to a supported method on Spring's transaction abstraction. */
    public static boolean isTransactionCallback(@Nullable PsiMethodCallExpression call) {
        if (call == null) return false;
        String name = call.getMethodExpression().getReferenceName();
        if (!CALLBACK_METHODS.contains(name)) return false;
        PsiMethod method = call.resolveMethod();
        return method != null && isTransactionOperationsType(method.getContainingClass());
    }

    public static boolean isReactiveExecute(@Nullable PsiMethodCallExpression call) {
        return resolvesTo(call, "execute", TRANSACTIONAL_OPERATOR);
    }

    public static boolean isReactiveTransactional(@Nullable PsiMethodCallExpression call) {
        return resolvesTo(call, "transactional", TRANSACTIONAL_OPERATOR);
    }

    /** Recognizes Reactor's documented {@code publisher.as(operator::transactional)} shape. */
    public static boolean isReactiveAsBoundary(@Nullable PsiMethodCallExpression call) {
        if (call == null || !"as".equals(call.getMethodExpression().getReferenceName())) return false;
        if (call.getArgumentList().getExpressionCount() != 1) return false;
        if (!(call.getArgumentList().getExpressions()[0] instanceof PsiMethodReferenceExpression reference)) {
            return false;
        }
        PsiElement resolved = reference.resolve();
        return resolved instanceof PsiMethod method
                && "transactional".equals(method.getName())
                && isTypeOrInheritor(method.getContainingClass(), TRANSACTIONAL_OPERATOR);
    }

    public static boolean isManagerCall(@Nullable PsiMethodCallExpression call, @NotNull String name) {
        return resolvesTo(call, name, PLATFORM_TRANSACTION_MANAGER);
    }

    public static boolean isPublisherType(@Nullable PsiType type) {
        return type instanceof PsiClassType classType
                && isTypeOrInheritor(classType.resolve(), REACTIVE_STREAMS_PUBLISHER);
    }

    private static boolean resolvesTo(@Nullable PsiMethodCallExpression call,
                                      @NotNull String name,
                                      @NotNull String ownerFqn) {
        if (call == null || !name.equals(call.getMethodExpression().getReferenceName())) return false;
        PsiMethod method = call.resolveMethod();
        return method != null && isTypeOrInheritor(method.getContainingClass(), ownerFqn);
    }

    private static boolean isTransactionOperationsType(@Nullable PsiClass candidate) {
        if (candidate == null) return false;
        String qualifiedName = candidate.getQualifiedName();
        if (TRANSACTION_OPERATIONS.equals(qualifiedName) || TRANSACTION_TEMPLATE.equals(qualifiedName)) {
            return true;
        }
        PsiClass operations = JavaPsiFacade.getInstance(candidate.getProject()).findClass(
                TRANSACTION_OPERATIONS, GlobalSearchScope.allScope(candidate.getProject()));
        return operations != null && candidate.isInheritor(operations, true);
    }

    private static boolean isTypeOrInheritor(@Nullable PsiClass candidate, @NotNull String baseFqn) {
        if (candidate == null) return false;
        if (baseFqn.equals(candidate.getQualifiedName())) return true;
        PsiClass base = JavaPsiFacade.getInstance(candidate.getProject()).findClass(
                baseFqn, GlobalSearchScope.allScope(candidate.getProject()));
        return base != null && candidate.isInheritor(base, true);
    }
}
