package com.repoinspector.analysis;

import com.intellij.openapi.application.ApplicationManager;
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
import com.repoinspector.constants.SpringAnnotations;
import com.repoinspector.model.EndpointInfo;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Discovers all Spring MVC REST endpoint methods in the project by searching
 * for the standard HTTP mapping annotations.
 * All methods must be called inside a read action.
 */
public final class EndpointFinder {

    private static final Logger LOG = Logger.getInstance(EndpointFinder.class);

    // Mapping annotation FQNs → default HTTP method label
    private static final String[][] MAPPING_ANNOTATIONS = SpringAnnotations.HTTP_MAPPING_ANNOTATIONS;

    private EndpointFinder() {
        // utility class
    }

    /**
     * Finds all API endpoint methods in the project.
     * Safe to call from a background thread (wraps PSI access in a read action).
     *
     * @param project the current project
     * @return list of EndpointInfo records, one per annotated method
     */
    public static List<EndpointInfo> findAllEndpoints(Project project) {
        AtomicReference<List<EndpointInfo>> holder = new AtomicReference<>();
        ProgressManager.getInstance().runProcess(
            () -> holder.set(ApplicationManager.getApplication().runReadAction((Computable<List<EndpointInfo>>) () -> {
                Set<EndpointInfo> results = new LinkedHashSet<>();
                GlobalSearchScope projectScope = GlobalSearchScope.projectScope(project);
                JavaPsiFacade facade = JavaPsiFacade.getInstance(project);

                for (String[] entry : MAPPING_ANNOTATIONS) {
                    String annotationFqn = entry[0];
                    String httpVerb = entry[1];

                    PsiClass annotationClass = facade.findClass(annotationFqn, GlobalSearchScope.allScope(project));
                    if (annotationClass == null) {
                        continue;
                    }

                    AnnotatedElementsSearch.searchPsiMethods(annotationClass, projectScope).forEach(method -> {
                        PsiClass containingClass = method.getContainingClass();
                        if (containingClass == null) {
                            return true; // continue
                        }
                        String controllerName = containingClass.getName() != null
                                ? containingClass.getName() : "Unknown";

                        PsiAnnotation annotation = method.getAnnotation(annotationFqn);
                        String methodPath   = extractPath(annotation);
                        String classPrefix  = extractClassPath(containingClass);
                        String path         = classPrefix.isEmpty() ? methodPath : classPrefix + methodPath;
                        String resolvedVerb = "REQUEST".equals(httpVerb)
                                ? extractRequestMethod(annotation) : httpVerb;

                        String signature = CallSiteAnalyzer.buildSignature(method);

                        String qualifiedName = containingClass.getQualifiedName();
                        results.add(new EndpointInfo(resolvedVerb, path, controllerName, signature,
                                qualifiedName, method));
                        return true; // continue forEach
                    });
                }

                LOG.info("findAllEndpoints: discovered " + results.size() + " endpoint(s)");
                return new ArrayList<>(results);
            })),
            new EmptyProgressIndicator()
        );
        return holder.get();
    }

    private static String extractClassPath(PsiClass cls) {
        PsiAnnotation mapping = cls.getAnnotation(SpringAnnotations.REQUEST_MAPPING);
        if (mapping == null) return "";
        String path = extractPath(mapping);
        return "/".equals(path) ? "" : path;
    }

    /**
     * Extracts the path value from a mapping annotation.
     * Handles both {@code @GetMapping("/path")} and {@code @GetMapping(value = "/path")}.
     */
    private static String extractPath(PsiAnnotation annotation) {
        if (annotation == null) {
            return "/";
        }

        // Try "value" attribute first, then "path"
        for (String attr : new String[]{"value", "path"}) {
            PsiAnnotationMemberValue memberValue = annotation.findAttributeValue(attr);
            if (memberValue == null) {
                continue;
            }
            String extracted = extractStringValue(memberValue);
            if (extracted != null && !extracted.isEmpty()) {
                return extracted;
            }
        }

        return "/";
    }

    /**
     * Extracts the HTTP method from a @RequestMapping annotation's {@code method} attribute.
     */
    private static String extractRequestMethod(PsiAnnotation annotation) {
        if (annotation == null) {
            return "REQUEST";
        }
        PsiAnnotationMemberValue methodValue = annotation.findAttributeValue("method");
        if (methodValue == null) {
            return "REQUEST";
        }
        // RequestMethod.GET → last segment after '.'
        String text = methodValue.getText();
        int dot = text.lastIndexOf('.');
        return dot >= 0 ? text.substring(dot + 1) : text;
    }

    /**
     * Extracts a string literal from an annotation attribute value,
     * handling both single values and array initializers (first element only).
     */
    private static String extractStringValue(PsiAnnotationMemberValue value) {
        if (value instanceof PsiLiteralExpression literal) {
            Object v = literal.getValue();
            return v != null ? v.toString() : null;
        }
        if (value instanceof PsiArrayInitializerMemberValue array) {
            PsiAnnotationMemberValue[] initializers = array.getInitializers();
            if (initializers.length > 0) {
                return extractStringValue(initializers[0]);
            }
        }
        return null;
    }
}
