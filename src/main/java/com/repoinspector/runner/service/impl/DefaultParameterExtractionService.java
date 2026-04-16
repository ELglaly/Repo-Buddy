package com.repoinspector.runner.service.impl;

import com.intellij.openapi.components.Service;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiType;
import com.repoinspector.runner.model.ParameterDef;
import com.repoinspector.runner.service.api.ParameterExtractionService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/** Stateless application-level service for extracting parameter metadata from PSI methods. */
@Service(Service.Level.APP)
public final class DefaultParameterExtractionService implements ParameterExtractionService {

    @Override
    public List<ParameterDef> extract(PsiMethod method) {
        PsiParameter[] params = method.getParameterList().getParameters();
        List<ParameterDef> defs = new ArrayList<>(params.length);
        for (PsiParameter p : params) {
            defs.add(new ParameterDef(p.getName(), p.getType().getPresentableText()));
        }
        return List.copyOf(defs);
    }

    @Override
    public String summary(PsiMethod method) {
        PsiParameter[] params = method.getParameterList().getParameters();
        if (params.length == 0) return "no params";
        StringBuilder sb = new StringBuilder();
        for (PsiParameter p : params) {
            if (!sb.isEmpty()) sb.append(", ");
            sb.append(p.getName()).append(": ").append(p.getType().getPresentableText());
        }
        return sb.toString();
    }

    @Override
    public String buildSignature(PsiMethod method) {
        String params = Arrays.stream(method.getParameterList().getParameters())
                .map(PsiParameter::getType)
                .map(PsiType::getPresentableText)
                .collect(Collectors.joining(", "));
        return method.getName() + "(" + params + ")";
    }
}
