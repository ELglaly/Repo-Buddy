package com.repoinspector.analysis;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.JavaRecursiveElementVisitor;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiType;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.ClassInheritorsSearch;
import com.repoinspector.constants.SpringAnnotations;
import com.repoinspector.model.CallChainNode;
import com.repoinspector.model.EndpointInfo;
import com.repoinspector.model.OperationType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Performs depth-first static call-chain analysis starting from an API endpoint method,
 * collecting every repository method reachable in the call tree.
 *
 * <p>Key guarantees:
 * <ul>
 *   <li>Cycle-safe: a {@code Set<PsiMethod>} prevents revisiting any method.</li>
 *   <li>Polymorphism: interface/abstract methods are resolved to concrete implementations.</li>
 *   <li>Dynamic-dispatch detection: {@code ApplicationContext.getBean()} calls are flagged.</li>
 *   <li>All PSI access is wrapped in a read action.</li>
 * </ul>
 */
public final class CallChainAnalyzer {

    private static final Logger LOG = Logger.getInstance(CallChainAnalyzer.class);

    private static final String TRANSACTIONAL_ANNOTATION = SpringAnnotations.TRANSACTIONAL;
    private static final String APPLICATION_CONTEXT_FQN  = SpringAnnotations.APPLICATION_CONTEXT;
    private static final int MAX_DEPTH = 20; // safety guard for extremely deep chains

    private CallChainAnalyzer() {
        // utility class
    }

    /**
     * Runs the call-chain analysis for the given endpoint.
     * Safe to call from a background thread (wraps PSI access in a read action).
     *
     * @param endpoint the API endpoint to start from
     * @param project  the current project
     * @return ordered list of {@link CallChainNode} records, starting with the endpoint itself
     */
    public static List<CallChainNode> analyze(EndpointInfo endpoint, Project project) {
        return ApplicationManager.getApplication().runReadAction((Computable<List<CallChainNode>>) () -> {
            List<CallChainNode> result = new ArrayList<>();
            Set<PsiMethod> visited = new HashSet<>();

            if (endpoint.psiMethod() != null) {
                dfs(endpoint.psiMethod(), 0, visited, result, project);
            }

            LOG.info("analyze: endpoint=" + endpoint.httpMethod() + " " + endpoint.path()
                    + " chain_size=" + result.size());
            return result;
        });
    }

    // -------------------------------------------------------------------------
    // DFS traversal
    // -------------------------------------------------------------------------

    private static void dfs(
            PsiMethod method,
            int depth,
            Set<PsiMethod> visited,
            List<CallChainNode> result,
            Project project
    ) {
        if (method == null || depth > MAX_DEPTH || visited.contains(method)) {
            return;
        }
        visited.add(method);

        PsiClass containingClass = method.getContainingClass();
        if (containingClass == null) {
            return;
        }

        boolean isRepo = RepositoryFinder.isRepository(containingClass, project);
        boolean isTransactional = isTransactional(method, containingClass);
        String className = containingClass.getName() != null ? containingClass.getName() : "Unknown";
        String signature = CallSiteAnalyzer.buildSignature(method);

        if (isRepo) {
            OperationType opType = RepositoryOperationClassifier.classify(method);
            String entity = RepositoryOperationClassifier.extractEntityName(containingClass);
            result.add(new CallChainNode(className, signature, depth, true, opType, entity,
                    isTransactional, false, method));
            // Do not recurse into repository internals
            return;
        }

        result.add(new CallChainNode(className, signature, depth, false, OperationType.UNKNOWN,
                "", isTransactional, false, method));

        // Collect all outgoing method calls in the method body
        List<PsiMethod> callees = collectCallers(method, project, result, depth);
        for (PsiMethod callee : callees) {
            dfs(callee, depth + 1, visited, result, project);
        }
    }

    // -------------------------------------------------------------------------
    // Callee collection
    // -------------------------------------------------------------------------

    /**
     * Visits all method call expressions in {@code method}'s body and resolves them
     * to concrete {@link PsiMethod} instances. Dynamic calls via {@code getBean()} are
     * added directly to {@code result} as dynamic nodes.
     */
    private static List<PsiMethod> collectCallers(
            PsiMethod method,
            Project project,
            List<CallChainNode> result,
            int depth
    ) {
        List<PsiMethod> callees = new ArrayList<>();

        method.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitMethodCallExpression(PsiMethodCallExpression call) {
                super.visitMethodCallExpression(call);

                // Detect ApplicationContext.getBean() — dynamic, cannot trace
                if (isDynamicBeanAccess(call, project)) {
                    result.add(new CallChainNode("ApplicationContext", "getBean(...)",
                            depth + 1, false, OperationType.UNKNOWN, "",
                            false, true, null));
                    return;
                }

                PsiMethod resolved = call.resolveMethod();
                if (resolved == null) {
                    return;
                }

                // Attempt to resolve interfaces/abstract methods to concrete implementations
                PsiMethod concrete = resolveToConcreteMethod(resolved, project);
                callees.add(concrete);
            }
        });

        return callees;
    }

    // -------------------------------------------------------------------------
    // Polymorphism resolution
    // -------------------------------------------------------------------------

    /**
     * If {@code method} belongs to an interface or abstract class, searches for
     * a concrete (non-abstract) implementing class and returns its override.
     * Falls back to {@code method} itself if no concrete implementation is found.
     */
    private static PsiMethod resolveToConcreteMethod(PsiMethod method, Project project) {
        PsiClass containingClass = method.getContainingClass();
        if (containingClass == null) {
            return method;
        }

        boolean isAbstract = containingClass.isInterface()
                || containingClass.hasModifierProperty("abstract");
        if (!isAbstract) {
            return method; // already concrete
        }

        GlobalSearchScope scope = GlobalSearchScope.projectScope(project);
        for (PsiClass implementor : ClassInheritorsSearch.search(containingClass, scope, true)) {
            if (implementor.isInterface() || implementor.hasModifierProperty("abstract")) {
                continue;
            }
            PsiMethod[] overrides = implementor.findMethodsBySignature(method, false);
            if (overrides.length > 0) {
                return overrides[0];
            }
        }

        return method; // no concrete implementation found in project scope
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static boolean isTransactional(PsiMethod method, PsiClass containingClass) {
        return method.hasAnnotation(TRANSACTIONAL_ANNOTATION)
                || containingClass.hasAnnotation(TRANSACTIONAL_ANNOTATION);
    }

    /**
     * Returns true if {@code call} is a {@code getBean(...)} call on an object
     * whose declared type is assignable to {@code ApplicationContext}.
     */
    private static boolean isDynamicBeanAccess(PsiMethodCallExpression call, Project project) {
        PsiReferenceExpression methodExpr = call.getMethodExpression();
        if (!"getBean".equals(methodExpr.getReferenceName())) {
            return false;
        }
        PsiExpression qualifier = methodExpr.getQualifierExpression();
        if (qualifier == null) {
            return false;
        }
        PsiType qualifierType = qualifier.getType();
        if (!(qualifierType instanceof PsiClassType classType)) {
            return false;
        }
        PsiClass resolvedClass = classType.resolve();
        if (resolvedClass == null) {
            return false;
        }
        JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
        PsiClass appCtxClass = facade.findClass(APPLICATION_CONTEXT_FQN,
                GlobalSearchScope.allScope(project));
        return appCtxClass != null && resolvedClass.isInheritor(appCtxClass, true);
    }
}
