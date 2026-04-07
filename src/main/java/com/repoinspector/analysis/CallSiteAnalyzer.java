package com.repoinspector.analysis;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.repoinspector.model.RepositoryMethodInfo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Analyzes call sites for Spring repository methods using ReferencesSearch.
 * All PSI operations are wrapped in read actions.
 */
public final class CallSiteAnalyzer {

    private CallSiteAnalyzer() {
        // utility class
    }

    /**
     * Holds the results of a full analysis pass, pairing info records
     * with their corresponding PsiMethod references for navigation.
     */
    public static final class AnalysisResult {
        private final List<RepositoryMethodInfo> infos;
        private final List<PsiMethod> methods;

        private AnalysisResult(List<RepositoryMethodInfo> infos, List<PsiMethod> methods) {
            this.infos = List.copyOf(infos);
            this.methods = List.copyOf(methods);
        }

        public List<RepositoryMethodInfo> getInfos() {
            return infos;
        }

        public List<PsiMethod> getMethods() {
            return methods;
        }
    }

    /**
     * Counts how many times a given method is referenced within the project scope.
     * Must be called inside a read action.
     *
     * @param method  the PSI method to search for
     * @param project the current project
     * @return number of call sites found
     */
    public static int countCallSites(PsiMethod method, Project project) {
        GlobalSearchScope scope = GlobalSearchScope.projectScope(project);
        return ReferencesSearch.search(method, scope).findAll().size();
    }

    /**
     * Runs a full analysis of all Spring repository methods in the project.
     * Wraps all PSI access in a read action and is safe to call from a background thread.
     *
     * @param project the current project
     * @return an AnalysisResult pairing RepositoryMethodInfo records with PsiMethod references
     */
    public static AnalysisResult analyzeAll(Project project) {
        return ApplicationManager.getApplication().runReadAction((Computable<AnalysisResult>) () -> {
            List<RepositoryMethodInfo> infos = new ArrayList<>();
            List<PsiMethod> methods = new ArrayList<>();

            List<PsiClass> repos = RepositoryFinder.findAllRepositories(project);

            for (PsiClass repoClass : repos) {
                String repoName = repoClass.getName();
                if (repoName == null) {
                    continue;
                }

                for (PsiMethod method : RepositoryFinder.getRepositoryMethods(repoClass)) {
                    String methodName = method.getName();
                    String signature = buildSignature(method);
                    int count = countCallSites(method, project);

                    infos.add(new RepositoryMethodInfo(repoName, methodName, signature, count));
                    methods.add(method);
                }
            }

            return new AnalysisResult(infos, methods);
        });
    }

    /**
     * Builds a human-readable method signature string, e.g. {@code findById(Long)}.
     */
    public static String buildSignature(PsiMethod method) {
        String params = Arrays.stream(method.getParameterList().getParameters())
                .map(PsiParameter::getType)
                .map(t -> t.getPresentableText())
                .collect(Collectors.joining(", "));
        return method.getName() + "(" + params + ")";
    }
}
