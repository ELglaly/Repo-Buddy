package com.repoinspector.inspections.fix;

import com.intellij.codeInspection.LocalQuickFixOnPsiElement;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementFactory;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifierList;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.repoinspector.constants.SpringAnnotations;
import org.jetbrains.annotations.NotNull;

/**
 * Quick-fix that adds {@code @Transactional} (Spring) to a method that performs
 * write operations.
 */
public class AddTransactionalFix extends LocalQuickFixOnPsiElement {

    public AddTransactionalFix(@NotNull PsiMethod method) {
        super(method);
    }

    @Override
    public @NotNull String getText() {
        return "Annotate method with @Transactional";
    }

    @Override
    public @NotNull String getFamilyName() {
        return "Add @Transactional";
    }

    @Override
    public void invoke(@NotNull Project project,
                       @NotNull PsiFile file,
                       @NotNull PsiElement startElement,
                       @NotNull PsiElement endElement) {
        if (!(startElement instanceof PsiMethod method)) return;
        PsiModifierList modifiers = method.getModifierList();

        PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);
        PsiAnnotation annotation =
                factory.createAnnotationFromText("@" + SpringAnnotations.TRANSACTIONAL, method);

        PsiElement added = modifiers.addAfter(annotation, null);
        JavaCodeStyleManager.getInstance(project).shortenClassReferences(added);
    }
}
