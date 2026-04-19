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
import com.repoinspector.analysis.api.OperationClassifierService;
import com.repoinspector.analysis.api.RepositoryAnalysisService;
import com.repoinspector.model.OperationType;
import com.repoinspector.runner.model.ParameterDef;
import com.repoinspector.runner.service.api.ParameterExtractionService;
import com.repoinspector.runner.ui.RepoRunnerPopup;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.Collection;
import java.util.List;

/**
 * Provides a gutter icon next to every method declared in a Spring repository
 * interface (annotation-based or Spring Data hierarchy-based).
 *
 * <p>Uses the two-pass approach required by the IntelliJ Platform:
 * <ul>
 *   <li>{@link #getLineMarkerInfo} — fast pass, always returns {@code null}.</li>
 *   <li>{@link #collectSlowLineMarkers} — slow pass on a background thread with
 *       the read lock held; performs repository detection and marker creation.</li>
 * </ul>
 */
public class RepoMethodGutterProvider implements LineMarkerProvider {

    @Override
    public @Nullable LineMarkerInfo<?> getLineMarkerInfo(@NotNull PsiElement element) {
        return null;
    }

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
            if (!project.getService(RepositoryAnalysisService.class).isRepository(containingClass)) continue;

            Icon icon = iconForMethod(method);
            String tooltip = buildTooltip(containingClass.getName(), method);

            LineMarkerInfo<PsiElement> marker = new LineMarkerInfo<>(
                    element,
                    element.getTextRange(),
                    icon,
                    e -> tooltip,
                    (mouseEvent, el) -> openRunnerPopup(mouseEvent.getLocationOnScreen(), el, project),
                    GutterIconRenderer.Alignment.LEFT,
                    () -> tooltip
            );

            result.add(marker);
        }
    }

    // -------------------------------------------------------------------------
    // Icon selection — delegates to OperationClassifierService (single source of truth)
    // -------------------------------------------------------------------------

    static Icon iconForMethod(PsiMethod method) {
        OperationClassifierService svc =
                ApplicationManager.getApplication().getService(OperationClassifierService.class);
        OperationType type = svc.classify(method);
        return switch (type) {
            case READ  -> AllIcons.Actions.Find;
            case WRITE -> AllIcons.Actions.Edit;
            default    -> AllIcons.Actions.Execute;
        };
    }

    // -------------------------------------------------------------------------
    // Tooltip
    // -------------------------------------------------------------------------

    private static String buildTooltip(String className, PsiMethod method) {
        String paramSummary = ApplicationManager.getApplication()
                .getService(ParameterExtractionService.class).summary(method);
        return "RepoBuddy \u25B6 "
                + className + "."
                + method.getName() + "("
                + paramSummary + ")"
                + "  \u2014 click to run against the live app";
    }

    // -------------------------------------------------------------------------
    // Popup creation
    // -------------------------------------------------------------------------

    private static void openRunnerPopup(java.awt.Point screenPoint,
                                        PsiElement element, Project project) {
        ApplicationManager.getApplication().runReadAction(() -> {
            PsiMethod method = (PsiMethod) element.getParent();
            PsiClass cls = method.getContainingClass();

            String classFqn   = (cls != null && cls.getQualifiedName() != null)
                    ? cls.getQualifiedName() : "";
            String methodName = method.getName();
            List<ParameterDef> params = ApplicationManager.getApplication()
                    .getService(ParameterExtractionService.class).extract(method);

            javax.swing.SwingUtilities.invokeLater(() -> {
                RepoRunnerPopup popup = new RepoRunnerPopup(project, classFqn, methodName, params);
                popup.display(screenPoint);
            });
        });
    }
}
