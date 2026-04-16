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
import com.repoinspector.analysis.api.RepositoryAnalysisService;
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
 * <p>The icon is chosen by the inferred operation type of the method:
 * <ul>
 *   <li>{@link AllIcons.Actions#Find} (magnifier) — read-type methods:
 *       {@code find*}, {@code get*}, {@code count*}, {@code exists*},
 *       {@code read*}, {@code search*}, {@code query*}, {@code fetch*}</li>
 *   <li>{@link AllIcons.Actions#Edit} (pencil) — write-type methods:
 *       {@code save*}, {@code delete*}, {@code update*}, {@code create*},
 *       {@code insert*}, {@code persist*}, {@code remove*}</li>
 *   <li>{@link AllIcons.Actions#Execute} (play) — all other / unknown</li>
 * </ul>
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
            if (!project.getService(RepositoryAnalysisService.class).isRepository(containingClass)) continue;

            Icon icon = iconForMethod(method.getName());
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
    // Icon selection
    // -------------------------------------------------------------------------

    private static final String[] READ_PREFIXES  =
            { "find", "get", "count", "exists", "read", "search", "query", "fetch", "load", "list" };
    private static final String[] WRITE_PREFIXES =
            { "save", "saveAll", "delete", "deleteAll", "deleteBy", "update",
              "create", "insert", "persist", "remove", "flush", "merge" };

    /**
     * Returns the gutter icon that best represents the operation type inferred
     * from the method name prefix.
     */
    static Icon iconForMethod(String methodName) {
        String lower = methodName.toLowerCase();
        for (String prefix : READ_PREFIXES) {
            if (lower.startsWith(prefix)) return AllIcons.Actions.Find;
        }
        for (String prefix : WRITE_PREFIXES) {
            if (lower.startsWith(prefix)) return AllIcons.Actions.Edit;
        }
        return AllIcons.Actions.Execute;
    }

    // -------------------------------------------------------------------------
    // Tooltip
    // -------------------------------------------------------------------------

    /**
     * Builds a human-readable tooltip with the full repository class name,
     * method signature, and parameter list.
     */
    private static String buildTooltip(String className, PsiMethod method) {
        Project project = method.getProject();
        String paramSummary = ApplicationManager.getApplication().getService(ParameterExtractionService.class).summary(method);
        return "RepoBuddy \u25B6 "
                + className + "."
                + method.getName() + "("
                + paramSummary + ")"
                + "  \u2014 click to run against the live app";
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

            String classFqn   = (cls != null && cls.getQualifiedName() != null)
                    ? cls.getQualifiedName() : "";
            String methodName = method.getName();
            List<ParameterDef> params = ApplicationManager.getApplication().getService(ParameterExtractionService.class).extract(method);

            javax.swing.SwingUtilities.invokeLater(() -> {
                RepoRunnerPopup popup = new RepoRunnerPopup(project, classFqn, methodName, params);
                popup.display(screenPoint);
            });
        });
    }
}
