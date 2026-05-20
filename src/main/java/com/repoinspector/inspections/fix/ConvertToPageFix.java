package com.repoinspector.inspections.fix;

import com.intellij.codeInspection.LocalQuickFixOnPsiElement;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementFactory;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypeElement;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.repoinspector.constants.SpringAnnotations;
import org.jetbrains.annotations.NotNull;

/**
 * Quick-fix that converts an unbounded collection-returning repository method
 * into a paginated one: the return type becomes {@code Page<T>} and a
 * {@code Pageable} parameter is appended.
 */
public class ConvertToPageFix extends LocalQuickFixOnPsiElement {

    public ConvertToPageFix(@NotNull PsiMethod method) {
        super(method);
    }

    @Override
    public @NotNull String getText() {
        return "Return Page<…> and add a Pageable parameter";
    }

    @Override
    public @NotNull String getFamilyName() {
        return "Add pagination";
    }

    @Override
    public void invoke(@NotNull Project project,
                       @NotNull PsiFile file,
                       @NotNull PsiElement startElement,
                       @NotNull PsiElement endElement) {
        if (!(startElement instanceof PsiMethod method)) return;
        PsiTypeElement returnTypeElement = method.getReturnTypeElement();
        if (returnTypeElement == null) return;

        PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);

        String pageText = SpringAnnotations.PAGE + elementTypeSuffix(method.getReturnType());
        returnTypeElement.replace(factory.createTypeElementFromText(pageText, method));

        if (!hasPageable(method)) {
            PsiType pageableType =
                    factory.createTypeByFQClassName(SpringAnnotations.PAGEABLE, method.getResolveScope());
            PsiParameter pageable = factory.createParameter("pageable", pageableType);
            method.getParameterList().add(pageable);
        }

        JavaCodeStyleManager.getInstance(project).shortenClassReferences(method);
    }

    /** {@code <Element>} when the collection has a single type argument, otherwise empty (raw). */
    private static String elementTypeSuffix(PsiType returnType) {
        if (returnType instanceof PsiClassType classType) {
            PsiType[] args = classType.getParameters();
            if (args.length == 1) {
                return "<" + args[0].getCanonicalText() + ">";
            }
        }
        return "";
    }

    private static boolean hasPageable(@NotNull PsiMethod method) {
        for (PsiParameter p : method.getParameterList().getParameters()) {
            if (p.getType().getCanonicalText().startsWith(SpringAnnotations.PAGEABLE)) return true;
        }
        return false;
    }
}
