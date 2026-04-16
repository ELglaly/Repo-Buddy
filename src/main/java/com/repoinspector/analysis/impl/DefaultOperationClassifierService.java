package com.repoinspector.analysis.impl;

import com.intellij.openapi.components.Service;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiType;
import com.repoinspector.analysis.api.OperationClassifierService;
import com.repoinspector.constants.SpringAnnotations;
import com.repoinspector.model.OperationType;

/**
 * Stateless application-level service that classifies repository operations and
 * extracts entity type information from Spring Data generics.
 */
@Service(Service.Level.APP)
public final class DefaultOperationClassifierService implements OperationClassifierService {

    private static final String[] WRITE_PREFIXES = {
            "save", "delete", "update", "insert", "remove", "persist", "flush", "create", "merge"
    };
    private static final String[] READ_PREFIXES = {
            "find", "get", "count", "exists", "load", "fetch", "read", "query", "list", "search"
    };

    @Override
    public OperationType classify(PsiMethod method) {
        if (method.hasAnnotation(SpringAnnotations.MODIFYING)) {
            return OperationType.WRITE;
        }
        String name = method.getName().toLowerCase();
        for (String prefix : WRITE_PREFIXES) {
            if (name.startsWith(prefix)) return OperationType.WRITE;
        }
        for (String prefix : READ_PREFIXES) {
            if (name.startsWith(prefix)) return OperationType.READ;
        }
        return OperationType.UNKNOWN;
    }

    @Override
    public String extractEntityName(PsiClass repoClass) {
        if (repoClass == null) return "";
        PsiClassType[] supertypes = concat(
                repoClass.getExtendsListTypes(),
                repoClass.getImplementsListTypes()
        );
        for (PsiClassType supertype : supertypes) {
            String fqn = supertype.rawType().getCanonicalText();
            if (isSpringDataRepo(fqn)) {
                PsiType[] typeArgs = supertype.getParameters();
                if (typeArgs.length > 0) {
                    return typeArgs[0].getPresentableText();
                }
            }
        }
        return "";
    }

    @Override
    public boolean isTransactional(PsiMethod method) {
        if (method.hasAnnotation(SpringAnnotations.TRANSACTIONAL)) return true;
        PsiClass cls = method.getContainingClass();
        return cls != null && cls.hasAnnotation(SpringAnnotations.TRANSACTIONAL);
    }

    private static boolean isSpringDataRepo(String fqn) {
        for (String repoFqn : SpringAnnotations.SPRING_DATA_REPO_FQNS) {
            if (repoFqn.equals(fqn)) return true;
        }
        return fqn.startsWith("org.springframework.data.");
    }

    private static PsiClassType[] concat(PsiClassType[] a, PsiClassType[] b) {
        PsiClassType[] result = new PsiClassType[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }
}
