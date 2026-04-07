package com.repoinspector.gutter;

import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.LineMarkerProvider;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiMethod;
import com.repoinspector.analysis.CallSiteAnalyzer;
import com.repoinspector.analysis.RepositoryFinder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;

/**
 * Provides gutter icons next to each method declared in a Spring repository interface.
 * Uses the two-pass approach: fast pass returns null for all elements; slow pass
 * (which runs with a read lock on a background thread) performs the actual analysis.
 */
public class RepoCallCountGutterProvider implements LineMarkerProvider {

    /**
     * Fast pass — always returns null to avoid blocking the EDT.
     * The actual markers are produced in {@link #collectSlowLineMarkers}.
     */
    @Override
    public @Nullable LineMarkerInfo<?> getLineMarkerInfo(@NotNull PsiElement element) {
        return null;
    }

    /**
     * Slow pass — called on a background thread with a read lock already held.
     * Filters the supplied elements to find method name identifiers inside
     * Spring repository classes, then creates a gutter icon for each one.
     */
    @Override
    public void collectSlowLineMarkers(
            @NotNull List<? extends PsiElement> elements,
            @NotNull Collection<? super LineMarkerInfo<?>> result) {

        for (PsiElement element : elements) {
            // Only process PsiIdentifier leaf nodes
            if (!(element instanceof PsiIdentifier)) {
                continue;
            }

            // The identifier's parent must be a PsiMethod
            PsiElement parent = element.getParent();
            if (!(parent instanceof PsiMethod method)) {
                continue;
            }

            // The method's containing class must be a Spring repository
            PsiClass containingClass = method.getContainingClass();
            if (containingClass == null) {
                continue;
            }

            Project project = element.getProject();
            if (!RepositoryFinder.isRepository(containingClass, project)) {
                continue;
            }

            // Count call sites (read action is already held by the platform)
            int callCount = CallSiteAnalyzer.countCallSites(method, project);
            String tooltip = "Called " + callCount + " times in this project";

            LineMarkerInfo<PsiElement> marker = new LineMarkerInfo<>(
                    element,
                    element.getTextRange(),
                    AllIcons.Nodes.DataTables,
                    e -> tooltip,
                    null,
                    GutterIconRenderer.Alignment.LEFT,
                    () -> tooltip
            );

            result.add(marker);
        }
    }
}
