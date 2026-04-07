package com.repoinspector.analysis;

import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.AnnotatedElementsSearch;
import com.intellij.psi.search.searches.ClassInheritorsSearch;
import com.intellij.psi.JavaPsiFacade;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Locates all Spring repository classes/interfaces in a project using two strategies:
 * 1. Annotation-based: classes annotated with @org.springframework.stereotype.Repository
 * 2. Hierarchy-based: interfaces extending Spring Data repository base types
 */
public final class RepositoryFinder {

    private static final String REPOSITORY_ANNOTATION = "org.springframework.stereotype.Repository";

    private static final String[] SPRING_DATA_BASE_TYPES = {
            "org.springframework.data.repository.Repository",
            "org.springframework.data.repository.CrudRepository",
            "org.springframework.data.jpa.repository.JpaRepository",
            "org.springframework.data.repository.PagingAndSortingRepository"
    };

    private RepositoryFinder() {
        // utility class
    }

    /**
     * Finds all Spring repository classes/interfaces in the project scope.
     * Must be called inside a read action.
     *
     * @param project the current project
     * @return deduplicated list of repository PsiClass instances
     */
    public static List<PsiClass> findAllRepositories(Project project) {
        Set<PsiClass> results = new LinkedHashSet<>();
        GlobalSearchScope projectScope = GlobalSearchScope.projectScope(project);
        JavaPsiFacade facade = JavaPsiFacade.getInstance(project);

        // Strategy 1: annotation-based search
        PsiClass annotationClass = facade.findClass(REPOSITORY_ANNOTATION, GlobalSearchScope.allScope(project));
        if (annotationClass != null) {
            AnnotatedElementsSearch.searchPsiClasses(annotationClass, projectScope)
                    .forEach(results::add);
        }

        // Strategy 2: hierarchy-based search for Spring Data base types
        for (String fqn : SPRING_DATA_BASE_TYPES) {
            PsiClass baseClass = facade.findClass(fqn, GlobalSearchScope.allScope(project));
            if (baseClass == null) {
                continue;
            }
            ClassInheritorsSearch.search(baseClass, projectScope, true)
                    .forEach(results::add);
        }

        return new ArrayList<>(results);
    }

    /**
     * Returns only the methods declared directly on the given repository class,
     * excluding inherited methods from base Spring Data interfaces.
     * Must be called inside a read action.
     *
     * @param repoClass the repository PsiClass
     * @return list of directly declared PsiMethod instances
     */
    public static List<PsiMethod> getRepositoryMethods(PsiClass repoClass) {
        if (repoClass == null) {
            return List.of();
        }
        // getMethods() returns only directly declared methods, not inherited ones
        return List.of(repoClass.getMethods());
    }

    /**
     * Returns true if the given class is a Spring repository (annotation or hierarchy).
     * Must be called inside a read action.
     *
     * @param psiClass the class to check
     * @param project  the current project
     * @return true if psiClass is a Spring repository
     */
    public static boolean isRepository(PsiClass psiClass, Project project) {
        if (psiClass == null) {
            return false;
        }
        JavaPsiFacade facade = JavaPsiFacade.getInstance(project);

        // Check direct @Repository annotation
        if (psiClass.hasAnnotation(REPOSITORY_ANNOTATION)) {
            return true;
        }

        // Check if it extends any Spring Data base type
        for (String fqn : SPRING_DATA_BASE_TYPES) {
            PsiClass baseClass = facade.findClass(fqn, GlobalSearchScope.allScope(project));
            if (baseClass != null && psiClass.isInheritor(baseClass, true)) {
                return true;
            }
        }

        return false;
    }
}
