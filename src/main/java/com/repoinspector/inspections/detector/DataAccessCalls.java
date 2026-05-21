package com.repoinspector.inspections.detector;

import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.search.GlobalSearchScope;
import com.repoinspector.constants.SpringAnnotations;
import org.jetbrains.annotations.Nullable;

/**
 * PSI-aware classification of method calls that perform a database round-trip.
 *
 * <p>Combines repository detection (which needs type-hierarchy resolution) with
 * the name-based signals in {@link DataAccessSignals} for the JPA, Hibernate and
 * JDBC API surfaces.
 */
public final class DataAccessCalls {

    private DataAccessCalls() {}

    /**
     * True if {@code cls} is (or extends) a Spring Data repository, either by the
     * {@code @Repository} stereotype or by inheriting a Spring Data base interface.
     */
    public static boolean isSpringDataRepository(@Nullable PsiClass cls) {
        if (cls == null) return false;
        if (cls.hasAnnotation(SpringAnnotations.REPOSITORY)) return true;
        JavaPsiFacade facade = JavaPsiFacade.getInstance(cls.getProject());
        GlobalSearchScope scope = GlobalSearchScope.allScope(cls.getProject());
        for (String fqn : SpringAnnotations.SPRING_DATA_BASE_TYPES) {
            PsiClass base = facade.findClass(fqn, scope);
            if (base != null && (cls.equals(base) || cls.isInheritor(base, true))) return true;
        }
        return false;
    }

    /** True if the call resolves to a query/read that hits the database. */
    public static boolean isQueryCall(@Nullable PsiMethodCallExpression call) {
        return call != null && isQueryMethod(call.resolveMethod());
    }

    /**
     * True if {@code method} performs a database query/read: any Spring Data
     * repository method, a JPA {@code EntityManager}/{@code Query} executor, a
     * Hibernate {@code Session} read, or a {@code JdbcTemplate} query.
     */
    public static boolean isQueryMethod(@Nullable PsiMethod method) {
        if (method == null) return false;
        PsiClass owner = method.getContainingClass();
        if (owner == null) return false;

        if (isSpringDataRepository(owner)) return true;

        String name = method.getName();
        String fqn = owner.getQualifiedName();
        if (TransactionWriteSignals.isJpaPersistenceType(fqn)
                && DataAccessSignals.isEntityManagerQueryMethod(name)) return true;
        if (TransactionWriteSignals.isHibernateSessionType(fqn)
                && DataAccessSignals.isHibernateSessionReadMethod(name)) return true;
        if (TransactionWriteSignals.isJdbcTemplateType(fqn)
                && DataAccessSignals.isJdbcQueryMethod(name)) return true;
        return false;
    }
}
