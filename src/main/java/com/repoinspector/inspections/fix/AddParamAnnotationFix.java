package com.repoinspector.inspections.fix;

import com.intellij.codeInspection.LocalQuickFixOnPsiElement;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementFactory;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiModifierList;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.repoinspector.constants.SpringAnnotations;
import org.jetbrains.annotations.NotNull;

/**
 * Quick-fix that adds {@code @Param("name")} to a repository method parameter so
 * a JPQL named bind parameter resolves correctly.
 */
public class AddParamAnnotationFix extends LocalQuickFixOnPsiElement {

    private final String paramName;

    public AddParamAnnotationFix(@NotNull PsiParameter parameter, @NotNull String paramName) {
        super(parameter);
        this.paramName = paramName;
    }

    @Override
    public @NotNull String getText() {
        return "Add @Param(\"" + paramName + "\")";
    }

    @Override
    public @NotNull String getFamilyName() {
        return "Add @Param annotation";
    }

    @Override
    public void invoke(@NotNull Project project,
                       @NotNull PsiFile file,
                       @NotNull PsiElement startElement,
                       @NotNull PsiElement endElement) {
        if (!(startElement instanceof PsiParameter parameter)) return;
        PsiModifierList modifiers = parameter.getModifierList();
        if (modifiers == null) return;

        PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);
        PsiAnnotation annotation = factory.createAnnotationFromText(
                "@" + SpringAnnotations.PARAM + "(\"" + paramName + "\")", parameter);

        PsiElement added = modifiers.addAfter(annotation, null);
        JavaCodeStyleManager.getInstance(project).shortenClassReferences(added);
    }
}
