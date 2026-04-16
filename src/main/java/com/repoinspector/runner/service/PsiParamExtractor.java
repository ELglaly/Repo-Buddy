package com.repoinspector.runner.service;

import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import com.repoinspector.runner.model.ParameterDef;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared utility for extracting {@link ParameterDef} descriptors from a PSI method.
 *
 * <p>Must be called under a read action (the PSI tree must not be mutated
 * concurrently).  Both {@link com.repoinspector.gutter.RepoMethodGutterProvider}
 * and {@link com.repoinspector.ui.RepoInspectorPanel} delegate here to avoid
 * duplicating parameter-extraction logic (DRY).
 */
public final class PsiParamExtractor {

    private PsiParamExtractor() {}

    /**
     * Returns one {@link ParameterDef} per parameter declared by {@code method},
     * in declaration order.
     *
     * @param method the PSI method — caller must hold the read lock
     * @return immutable list of parameter descriptors; empty for no-arg methods
     */
    public static List<ParameterDef> extract(PsiMethod method) {
        PsiParameter[] params = method.getParameterList().getParameters();
        List<ParameterDef> defs = new ArrayList<>(params.length);
        for (PsiParameter p : params) {
            defs.add(new ParameterDef(p.getName(), p.getType().getPresentableText()));
        }
        return List.copyOf(defs);
    }

    /**
     * Builds a compact human-readable parameter summary, e.g.
     * {@code "id: Long, name: String"} or {@code "no params"} for zero-arg methods.
     */
    public static String summary(PsiMethod method) {
        PsiParameter[] params = method.getParameterList().getParameters();
        if (params.length == 0) return "no params";
        StringBuilder sb = new StringBuilder();
        for (PsiParameter p : params) {
            if (!sb.isEmpty()) sb.append(", ");
            sb.append(p.getName()).append(": ").append(p.getType().getPresentableText());
        }
        return sb.toString();
    }
}
