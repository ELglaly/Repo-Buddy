package com.repoinspector.analysis;

import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiType;
import com.repoinspector.model.OperationType;

/**
 * Classifies repository methods as READ or WRITE and extracts the entity generic type.
 * All methods must be called inside a read action.
 */
public final class RepositoryOperationClassifier {

    private static final String MODIFYING_ANNOTATION = "org.springframework.data.jpa.repository.Modifying";
    private static final String TRANSACTIONAL_ANNOTATION = "org.springframework.transaction.annotation.Transactional";

    private static final String[] WRITE_PREFIXES = {
            "save", "delete", "update", "insert", "remove", "persist", "flush", "create", "merge"
    };

    private static final String[] READ_PREFIXES = {
            "find", "get", "count", "exists", "load", "fetch", "read", "query", "list", "search"
    };

    // Spring Data repository FQNs whose first generic argument is the entity type
    private static final String[] SPRING_DATA_REPO_FQNS = {
            "org.springframework.data.jpa.repository.JpaRepository",
            "org.springframework.data.repository.CrudRepository",
            "org.springframework.data.repository.PagingAndSortingRepository",
            "org.springframework.data.repository.Repository",
            "org.springframework.data.mongodb.repository.MongoRepository"
    };

    private RepositoryOperationClassifier() {
        // utility class
    }

    /**
     * Classifies whether a repository method is a READ or WRITE operation.
     *
     * @param method the repository PsiMethod to classify
     * @return READ, WRITE, or UNKNOWN
     */
    public static OperationType classify(PsiMethod method) {
        // @Modifying always means WRITE
        if (method.hasAnnotation(MODIFYING_ANNOTATION)) {
            return OperationType.WRITE;
        }

        String name = method.getName().toLowerCase();

        for (String prefix : WRITE_PREFIXES) {
            if (name.startsWith(prefix)) {
                return OperationType.WRITE;
            }
        }

        for (String prefix : READ_PREFIXES) {
            if (name.startsWith(prefix)) {
                return OperationType.READ;
            }
        }

        return OperationType.UNKNOWN;
    }

    /**
     * Extracts the entity type name from the repository's Spring Data generic supertype.
     * For example, {@code UserRepository extends JpaRepository<User, Long>} returns {@code "User"}.
     *
     * @param repoClass the repository PsiClass
     * @return entity class simple name, or empty string if not determinable
     */
    public static String extractEntityName(PsiClass repoClass) {
        if (repoClass == null) {
            return "";
        }

        // Check both extends and implements lists
        PsiClassType[] supertypes = concat(
                repoClass.getExtendsListTypes(),
                repoClass.getImplementsListTypes()
        );

        for (PsiClassType supertype : supertypes) {
            String fqn = supertype.rawType().getCanonicalText();
            if (isSpringDataRepo(fqn)) {
                PsiType[] typeArgs = supertype.getParameters();
                if (typeArgs.length > 0) {
                    // First type argument is the entity type
                    return typeArgs[0].getPresentableText();
                }
            }
        }

        return "";
    }

    /**
     * Returns true if the method or its containing class has @Transactional.
     */
    public static boolean isTransactional(PsiMethod method) {
        if (method.hasAnnotation(TRANSACTIONAL_ANNOTATION)) {
            return true;
        }
        PsiClass containingClass = method.getContainingClass();
        return containingClass != null && containingClass.hasAnnotation(TRANSACTIONAL_ANNOTATION);
    }

    private static boolean isSpringDataRepo(String fqn) {
        for (String repoFqn : SPRING_DATA_REPO_FQNS) {
            if (repoFqn.equals(fqn)) {
                return true;
            }
        }
        // Also handle the generic "*Repository" pattern in Spring Data
        return fqn.startsWith("org.springframework.data.");
    }

    private static PsiClassType[] concat(PsiClassType[] a, PsiClassType[] b) {
        PsiClassType[] result = new PsiClassType[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }
}
