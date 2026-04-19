package com.repoinspector.analysis;

import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiType;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.openapi.project.Project;

import java.util.Arrays;
import java.util.stream.Collectors;

/** Utility for PSI-level call-site queries. Must be called inside a read action. */
public final class CallSiteAnalyzer {

    private CallSiteAnalyzer() {}

    /** Counts how many times {@code method} is referenced within the project scope. */
    public static int countCallSites(PsiMethod method, Project project) {
        GlobalSearchScope scope = GlobalSearchScope.projectScope(project);
        return ReferencesSearch.search(method, scope).findAll().size();
    }

    /** Builds a human-readable method signature, e.g. {@code findById(Long)}. */
    public static String buildSignature(PsiMethod method) {
        String params = Arrays.stream(method.getParameterList().getParameters())
                .map(PsiParameter::getType)
                .map(PsiType::getPresentableText)
                .collect(Collectors.joining(", "));
        return method.getName() + "(" + params + ")";
    }
}
