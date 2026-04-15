package com.repoinspector.gutter;

import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.LineMarkerProvider;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import com.repoinspector.analysis.RepositoryFinder;
import com.repoinspector.runner.model.ParameterDef;
import com.repoinspector.runner.ui.RepoRunnerPopup;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Provides a "Run ▶" gutter icon next to every method declared in a Spring
 * repository interface (annotation-based or Spring Data hierarchy-based).
 *
 * <p>Clicking the icon opens {@link RepoRunnerPopup}, which lets the developer
 * enter parameter values and execute the method against the live application.
 *
 * <p>Uses the two-pass approach required by the IntelliJ Platform:
 * <ul>
 *   <li>{@link #getLineMarkerInfo} — fast pass, always returns {@code null}.</li>
 *   <li>{@link #collectSlowLineMarkers} — slow pass on a background thread with
 *       the read lock held; performs repository detection and marker creation.</li>
 * </ul>
 */
public class RepoMethodGutterProvider implements LineMarkerProvider {

    /** Fast pass — never blocks the EDT. */
    @Override
    public @Nullable LineMarkerInfo<?> getLineMarkerInfo(@NotNull PsiElement element) {
        return null;
    }

    /** Slow pass — runs on a background thread; read lock is already held by the platform. */
    @Override
    public void collectSlowLineMarkers(
            @NotNull List<? extends PsiElement> elements,
            @NotNull Collection<? super LineMarkerInfo<?>> result) {

        for (PsiElement element : elements) {
            if (!(element instanceof PsiIdentifier)) continue;

            PsiElement parent = element.getParent();
            if (!(parent instanceof PsiMethod method)) continue;

            PsiClass containingClass = method.getContainingClass();
            if (containingClass == null) continue;

            String fqn = containingClass.getQualifiedName();
            if (fqn == null) continue;

            Project project = element.getProject();
            if (!RepositoryFinder.isRepository(containingClass, project)) continue;

            String tooltip = "RepoBuddy: run " + method.getName() + "() on the live app";

            LineMarkerInfo<PsiElement> marker = new LineMarkerInfo<>(
                    element,
                    element.getTextRange(),
                    AllIcons.Actions.Execute,
                    e -> tooltip,
                    (mouseEvent, el) -> openRunnerPopup(mouseEvent.getLocationOnScreen(), el, project),
                    GutterIconRenderer.Alignment.LEFT,
                    () -> tooltip
            );

            result.add(marker);
        }
    }

    // -------------------------------------------------------------------------
    // Popup creation
    // -------------------------------------------------------------------------

    /**
     * Reads PSI data under a read action, then opens the popup on the EDT.
     *
     * @param screenPoint screen coordinates from the gutter icon click
     * @param element     the {@link PsiIdentifier} node on which the marker was placed
     * @param project     the current project
     */
    private static void openRunnerPopup(java.awt.Point screenPoint,
                                        PsiElement element, Project project) {
        ApplicationManager.getApplication().runReadAction(() -> {
            PsiMethod method = (PsiMethod) element.getParent();
            PsiClass cls = method.getContainingClass();

            String classFqn  = cls != null && cls.getQualifiedName() != null
                    ? cls.getQualifiedName() : "";
            String methodName = method.getName();
            List<ParameterDef> params = extractParams(method);

            SwingUtilities.invokeLater(() -> {
                RepoRunnerPopup popup = new RepoRunnerPopup(project, classFqn, methodName, params);
                popup.display(screenPoint);
            });
        });
    }

    private static List<ParameterDef> extractParams(PsiMethod method) {
        List<ParameterDef> defs = new ArrayList<>();
        for (PsiParameter param : method.getParameterList().getParameters()) {
            defs.add(new ParameterDef(param.getName(), param.getType().getPresentableText()));
        }
        return defs;
    }
}
