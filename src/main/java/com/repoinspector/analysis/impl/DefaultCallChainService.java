package com.repoinspector.analysis.impl;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
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
import com.intellij.psi.util.PsiModificationTracker;
import com.repoinspector.analysis.api.CallChainService;
import com.repoinspector.analysis.api.OperationClassifierService;
import com.repoinspector.analysis.api.RepositoryAnalysisService;
import com.repoinspector.constants.SpringAnnotations;
import com.repoinspector.model.CallChainNode;
import com.repoinspector.model.EndpointInfo;
import com.repoinspector.model.OperationType;
import com.repoinspector.runner.service.api.ParameterExtractionService;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Project-level service that performs depth-first static call-chain analysis
 * from a Spring MVC endpoint to all reachable repository methods.
 *
 * <p>Results are cached per endpoint and automatically invalidated on any PSI modification.
 * Only derived data (strings, flags) is cached — live {@code PsiMethod} references are
 * stripped before storage so GC is not blocked across read actions.
 */
@Service(Service.Level.PROJECT)
public final class DefaultCallChainService implements CallChainService {

    private static final Logger LOG = Logger.getInstance(DefaultCallChainService.class);
    private static final int MAX_DEPTH = 20;

    private record CachedEntry(long modificationCount, List<CallChainNode> nodes) {}

    /** Returned by {@link #collectCallees} — callees to recurse into + dynamic sentinel nodes. */
    private record CollectResult(List<PsiMethod> callees, List<CallChainNode> dynamicNodes) {}

    // Cache key: "ControllerName#methodSignature" → PSI-free nodes
    private final Map<String, CachedEntry> cache = new ConcurrentHashMap<>();

    private final Project                  project;
    private final RepositoryAnalysisService  repoService;
    private final OperationClassifierService classifierService;
    private final ParameterExtractionService paramService;

    public DefaultCallChainService(@NotNull Project project) {
        this.project           = project;
        this.repoService       = project.getService(RepositoryAnalysisService.class);
        this.classifierService = ApplicationManager.getApplication()
                .getService(OperationClassifierService.class);
        this.paramService      = ApplicationManager.getApplication()
                .getService(ParameterExtractionService.class);
    }

    
    // CallChainService API
    @Override
    public List<CallChainNode> getOrAnalyze(EndpointInfo endpoint) {
        String key = cacheKey(endpoint);
        CachedEntry entry = cache.get(key);
        long currentStamp = PsiModificationTracker.getInstance(project).getModificationCount();

        if (entry != null && entry.modificationCount() == currentStamp) {
            return entry.nodes();
        }

        List<CallChainNode> nodes = analyze(endpoint);
        // Strip PSI before caching so GC can collect the PSI subtree.
        List<CallChainNode> cacheNodes = nodes.stream().map(CallChainNode::withoutPsi).toList();
        cache.put(key, new CachedEntry(currentStamp, cacheNodes));
        return nodes;
    }

    @Override
    public void clearCache() {
        cache.clear();
    }

    
    // DFS Analysis
    private List<CallChainNode> analyze(EndpointInfo endpoint) {
        return ApplicationManager.getApplication().runReadAction((Computable<List<CallChainNode>>) () -> {
            List<CallChainNode> result  = new ArrayList<>();
            Set<PsiMethod>      visited = new HashSet<>();
            if (endpoint.psiMethod() != null && endpoint.psiMethod().isValid()) {
                dfs(endpoint.psiMethod(), 0, visited, result);
            }
            LOG.info("analyze: " + endpoint.httpMethod() + " " + endpoint.path()
                    + " → chain_size=" + result.size());
            return result;
        });
    }

    private void dfs(PsiMethod method, int depth, Set<PsiMethod> visited, List<CallChainNode> result) {
        if (method == null || depth > MAX_DEPTH || visited.contains(method)) return;
        visited.add(method);

        PsiClass containingClass = method.getContainingClass();
        if (containingClass == null) return;

        boolean isRepo         = repoService.isRepository(containingClass);
        boolean isTransactional = classifierService.isTransactional(method);
        String  className      = containingClass.getName() != null ? containingClass.getName() : "Unknown";
        String  qualifiedName  = containingClass.getQualifiedName();
        String  signature      = paramService.buildSignature(method);

        if (isRepo) {
            OperationType opType = classifierService.classify(method);
            String        entity = classifierService.extractEntityName(containingClass);
            result.add(new CallChainNode(className, signature, depth, true, opType, entity,
                    isTransactional, false, qualifiedName, method));
            return;
        }

        result.add(new CallChainNode(className, signature, depth, false, OperationType.UNKNOWN,
                "", isTransactional, false, qualifiedName, method));

        CollectResult collected = collectCallees(method, depth);
        result.addAll(collected.dynamicNodes());
        for (PsiMethod callee : collected.callees()) {
            dfs(callee, depth + 1, visited, result);
        }
    }

    
    // Callee collection
    private CollectResult collectCallees(PsiMethod method, int depth) {
        List<PsiMethod> callees = new ArrayList<>();
        List<CallChainNode> dynamicNodes = new ArrayList<>();

        method.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitMethodCallExpression(@NotNull PsiMethodCallExpression call) {
                super.visitMethodCallExpression(call);

                if (isDynamicBeanAccess(call)) {
                    dynamicNodes.add(new CallChainNode("ApplicationContext", "getBean(...)",
                            depth + 1, false, OperationType.UNKNOWN, "", false, true, null, null));
                    return;
                }

                PsiMethod resolved = call.resolveMethod();
                if (resolved != null) {
                    callees.add(resolveToConcreteMethod(resolved));
                }
            }
        });

        return new CollectResult(callees, dynamicNodes);
    }

    
    // Polymorphism resolution
    private PsiMethod resolveToConcreteMethod(PsiMethod method) {
        PsiClass cls = method.getContainingClass();
        if (cls == null) return method;
        if (!cls.isInterface() && !cls.hasModifierProperty("abstract")) return method;

        GlobalSearchScope scope = GlobalSearchScope.projectScope(project);
        List<PsiMethod> candidates = new ArrayList<>();
        for (PsiClass impl : ClassInheritorsSearch.search(cls, scope, true)) {
            if (impl.isInterface() || impl.hasModifierProperty("abstract")) continue;
            PsiMethod[] overrides = impl.findMethodsBySignature(method, false);
            if (overrides.length > 0) candidates.add(overrides[0]);
        }

        if (candidates.isEmpty()) return method;
        if (candidates.size() > 1) {
            LOG.warn("resolveToConcreteMethod: " + candidates.size() + " implementations found for "
                    + cls.getQualifiedName() + "." + method.getName()
                    + " — using first; call chain may be inaccurate");
        }
        return candidates.get(0);
    }

    
    // Dynamic bean detection
    

    private boolean isDynamicBeanAccess(PsiMethodCallExpression call) {
        PsiReferenceExpression methodExpr = call.getMethodExpression();
        if (!"getBean".equals(methodExpr.getReferenceName())) return false;

        PsiExpression qualifier = methodExpr.getQualifierExpression();
        if (qualifier == null) return false;

        PsiType qualifierType = qualifier.getType();
        if (!(qualifierType instanceof PsiClassType classType)) return false;

        PsiClass resolvedClass = classType.resolve();
        if (resolvedClass == null) return false;

        JavaPsiFacade facade      = JavaPsiFacade.getInstance(project);
        PsiClass      appCtxClass = facade.findClass(SpringAnnotations.APPLICATION_CONTEXT,
                GlobalSearchScope.allScope(project));
        return appCtxClass != null && resolvedClass.isInheritor(appCtxClass, true);
    }

    
    // Helpers
    

    private static String cacheKey(EndpointInfo endpoint) {
        return endpoint.controllerName() + "#" + endpoint.methodSignature();
    }
}
