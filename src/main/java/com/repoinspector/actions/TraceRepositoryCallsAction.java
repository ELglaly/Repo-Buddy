package com.repoinspector.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentManager;
import com.repoinspector.analysis.api.CallChainService;
import com.repoinspector.analysis.api.EndpointAnalysisService;
import com.repoinspector.constants.SpringAnnotations;
import com.repoinspector.model.CallChainNode;
import com.repoinspector.model.EndpointInfo;
import com.repoinspector.runner.service.api.ParameterExtractionService;
import com.repoinspector.ui.CallChainPanel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.List;
import java.util.Optional;

/**
 * Editor right-click action: "Trace Repository Calls for This API".
 *
 * <p>Only enabled when the caret is inside a Spring mapping-annotated method.
 * When triggered it opens the "RepoBuddy" tool window, selects the
 * "Call Chain Tracer" tab, and kicks off the trace automatically.
 */
public class TraceRepositoryCallsAction extends AnAction {

    private static final Logger LOG = Logger.getInstance(TraceRepositoryCallsAction.class);

    /** Data extracted from PSI inside a single read action to avoid repeated locking. */
    private record EndpointData(String signature, String controllerClass) {}

    @Override
    public void update(@NotNull AnActionEvent e) {
        boolean enabled = ApplicationManager.getApplication().runReadAction(
                (Computable<Boolean>) () -> {
                    PsiMethod method = getMethodUnderCaret(e);
                    return method != null && isEndpointMethod(method);
                });
        e.getPresentation().setEnabledAndVisible(enabled);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;

        // All PSI access in one read action at the start.
        EndpointData data = ApplicationManager.getApplication().runReadAction(
                (Computable<EndpointData>) () -> {
                    PsiMethod method = getMethodUnderCaret(e);
                    if (method == null || !isEndpointMethod(method) || !method.isValid()) return null;
                    ParameterExtractionService paramService = ApplicationManager.getApplication()
                            .getService(ParameterExtractionService.class);
                    String sig = paramService.buildSignature(method);
                    PsiClass cls = method.getContainingClass();
                    String className = (cls != null && cls.getName() != null) ? cls.getName() : "";
                    return new EndpointData(sig, className);
                });

        if (data == null) return;

        ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow("RepoBuddy");
        if (toolWindow == null) return;
        toolWindow.show();

        ContentManager cm = toolWindow.getContentManager();
        if (cm.getContentCount() > 1) {
            cm.setSelectedContent(cm.getContent(1));
        }

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            EndpointAnalysisService endpointService = project.getService(EndpointAnalysisService.class);
            List<EndpointInfo> endpoints = endpointService.findAllEndpoints();

            Optional<EndpointInfo> matchOpt = endpoints.stream()
                    .filter(ep -> ep.methodSignature().equals(data.signature())
                            && ep.controllerName().equals(data.controllerClass()))
                    .findFirst();

            if (matchOpt.isEmpty()) {
                LOG.info("TraceRepositoryCallsAction: no matching endpoint found for "
                        + data.controllerClass() + "." + data.signature());
                return;
            }

            EndpointInfo match = matchOpt.get();
            List<CallChainNode> result = project.getService(CallChainService.class).getOrAnalyze(match);

            SwingUtilities.invokeLater(() -> {
                if (cm.getContentCount() > 1) {
                    Content chainContent = cm.getContent(1);
                    if (chainContent != null
                            && chainContent.getComponent() instanceof CallChainPanel panel) {
                        panel.showResult(match, result);
                    }
                }
            });
        });
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    @Nullable
    private static PsiMethod getMethodUnderCaret(AnActionEvent e) {
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        PsiFile file = e.getData(CommonDataKeys.PSI_FILE);
        if (editor == null || file == null) return null;
        int offset = editor.getCaretModel().getOffset();
        PsiElement element = file.findElementAt(offset);
        return PsiTreeUtil.getParentOfType(element, PsiMethod.class);
    }

    private static boolean isEndpointMethod(PsiMethod method) {
        for (String fqn : SpringAnnotations.HTTP_MAPPING_FQNS) {
            if (method.hasAnnotation(fqn)) return true;
        }
        return false;
    }
}
