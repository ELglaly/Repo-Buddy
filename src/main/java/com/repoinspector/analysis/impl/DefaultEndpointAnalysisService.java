package com.repoinspector.analysis.impl;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiArrayInitializerMemberValue;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.AnnotatedElementsSearch;
import com.repoinspector.analysis.api.EndpointAnalysisService;
import com.repoinspector.constants.SpringAnnotations;
import com.repoinspector.model.EndpointInfo;
import com.repoinspector.runner.service.api.ParameterExtractionService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/** Project-level service for discovering Spring MVC REST endpoint methods. */
@Service(Service.Level.PROJECT)
public final class DefaultEndpointAnalysisService implements EndpointAnalysisService {

    private static final Logger LOG = Logger.getInstance(DefaultEndpointAnalysisService.class);

    private final Project project;

    public DefaultEndpointAnalysisService(@NotNull Project project) {
        this.project = project;
    }

    @Override
    public List<EndpointInfo> findAllEndpoints() {
        AtomicReference<List<EndpointInfo>> holder = new AtomicReference<>();
        ProgressManager.getInstance().runProcess(
            () -> holder.set(ApplicationManager.getApplication().runReadAction((Computable<List<EndpointInfo>>) () -> {
                ParameterExtractionService paramService =
                        ApplicationManager.getApplication().getService(ParameterExtractionService.class);

                Set<EndpointInfo> results     = new LinkedHashSet<>();
                GlobalSearchScope projectScope = GlobalSearchScope.projectScope(project);
                JavaPsiFacade     facade       = JavaPsiFacade.getInstance(project);

                for (String[] entry : SpringAnnotations.HTTP_MAPPING_ANNOTATIONS) {
                    String annotationFqn = entry[0];
                    String httpVerb      = entry[1];

                    PsiClass annotationClass = facade.findClass(annotationFqn, GlobalSearchScope.allScope(project));
                    if (annotationClass == null) continue;

                    AnnotatedElementsSearch.searchPsiMethods(annotationClass, projectScope).forEach(method -> {
                        PsiClass containingClass = method.getContainingClass();
                        if (containingClass == null) return true;

                        String controllerName = containingClass.getName() != null
                                ? containingClass.getName() : "Unknown";

                        PsiAnnotation annotation   = method.getAnnotation(annotationFqn);
                        String        methodPath   = extractPath(annotation);
                        String        classPrefix  = extractClassPath(containingClass);
                        String        path         = classPrefix.isEmpty() ? methodPath : classPrefix + methodPath;
                        String        resolvedVerb = "REQUEST".equals(httpVerb)
                                ? extractRequestMethod(annotation) : httpVerb;
                        String        signature    = paramService.buildSignature(method);

                        String qualifiedName = containingClass.getQualifiedName();
                        results.add(new EndpointInfo(resolvedVerb, path, controllerName, signature,
                                qualifiedName, method));
                        return true;
                    });
                }

                LOG.info("findAllEndpoints: discovered " + results.size() + " endpoint(s)");
                return new ArrayList<>(results);
            })),
            new EmptyProgressIndicator()
        );
        return holder.get();
    }

    // ── private helpers ────────────────────────────────────────────────────────

    private static String extractClassPath(@NotNull PsiClass cls) {
        PsiAnnotation mapping = cls.getAnnotation(SpringAnnotations.REQUEST_MAPPING);
        if (mapping == null) return "";
        String path = extractPath(mapping);
        return "/".equals(path) ? "" : path;
    }

    private static String extractPath(@Nullable PsiAnnotation annotation) {
        if (annotation == null) return "/";
        for (String attr : new String[]{"value", "path"}) {
            PsiAnnotationMemberValue memberValue = annotation.findAttributeValue(attr);
            if (memberValue == null) continue;
            String extracted = extractStringValue(memberValue);
            if (extracted != null && !extracted.isEmpty()) return extracted;
        }
        return "/";
    }

    private static String extractRequestMethod(@Nullable PsiAnnotation annotation) {
        if (annotation == null) return "REQUEST";
        PsiAnnotationMemberValue methodValue = annotation.findAttributeValue("method");
        if (methodValue == null) return "REQUEST";
        String text = methodValue.getText();
        int dot = text.lastIndexOf('.');
        return dot >= 0 ? text.substring(dot + 1) : text;
    }

    @Nullable
    private static String extractStringValue(PsiAnnotationMemberValue value) {
        if (value instanceof PsiLiteralExpression literal) {
            Object v = literal.getValue();
            return v != null ? v.toString() : null;
        }
        if (value instanceof PsiArrayInitializerMemberValue array) {
            PsiAnnotationMemberValue[] initializers = array.getInitializers();
            if (initializers.length > 0) return extractStringValue(initializers[0]);
        }
        return null;
    }
}
