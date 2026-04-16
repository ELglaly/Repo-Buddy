package com.repoinspector.analysis.impl;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.AnnotatedElementsSearch;
import com.intellij.psi.search.searches.ClassInheritorsSearch;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.repoinspector.analysis.api.RepositoryAnalysisService;
import com.repoinspector.constants.SpringAnnotations;
import com.repoinspector.model.RepositoryMethodInfo;
import com.repoinspector.runner.service.api.ParameterExtractionService;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/** Project-level service for discovering Spring repositories and counting call sites. */
@Service(Service.Level.PROJECT)
public final class DefaultRepositoryAnalysisService implements RepositoryAnalysisService {

    private static final Logger LOG = Logger.getInstance(DefaultRepositoryAnalysisService.class);

    private final Project project;

    public DefaultRepositoryAnalysisService(@NotNull Project project) {
        this.project = project;
    }

    @Override
    public AnalysisResult analyzeAll() {
        AtomicReference<AnalysisResult> holder = new AtomicReference<>();
        ProgressManager.getInstance().runProcess(
            () -> holder.set(ApplicationManager.getApplication().runReadAction((Computable<AnalysisResult>) () -> {
                ParameterExtractionService paramService =
                        ApplicationManager.getApplication().getService(ParameterExtractionService.class);

                List<RepositoryMethodInfo> infos   = new ArrayList<>();
                List<PsiMethod>            methods = new ArrayList<>();

                List<PsiClass> repos = discoverRepositories();
                LOG.info("analyzeAll: discovered " + repos.size() + " repository class(es)");

                for (PsiClass repoClass : repos) {
                    String repoName = repoClass.getName();
                    if (repoName == null) continue;
                    for (PsiMethod method : getRepositoryMethods(repoClass)) {
                        String signature = paramService.buildSignature(method);
                        int    count     = countCallSites(method);
                        infos.add(new RepositoryMethodInfo(repoName, method.getName(), signature, count));
                        methods.add(method);
                    }
                }
                return new AnalysisResult(infos, methods);
            })),
            new EmptyProgressIndicator()
        );
        return holder.get();
    }

    @Override
    public boolean isRepository(PsiClass psiClass) {
        if (psiClass == null) return false;
        if (psiClass.hasAnnotation(SpringAnnotations.REPOSITORY)) return true;
        JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
        for (String fqn : SpringAnnotations.SPRING_DATA_BASE_TYPES) {
            PsiClass base = facade.findClass(fqn, GlobalSearchScope.allScope(project));
            if (base != null && psiClass.isInheritor(base, true)) return true;
        }
        return false;
    }

    @Override
    public List<PsiMethod> getRepositoryMethods(PsiClass repoClass) {
        if (repoClass == null) return List.of();
        return List.of(repoClass.getMethods());
    }

    // ── private helpers ────────────────────────────────────────────────────────

    /** Must be called inside a read action. */
    private List<PsiClass> discoverRepositories() {
        Set<PsiClass>     results      = new LinkedHashSet<>();
        GlobalSearchScope projectScope = GlobalSearchScope.projectScope(project);
        JavaPsiFacade     facade       = JavaPsiFacade.getInstance(project);

        PsiClass annotationClass = facade.findClass(SpringAnnotations.REPOSITORY,
                GlobalSearchScope.allScope(project));
        if (annotationClass != null) {
            AnnotatedElementsSearch.searchPsiClasses(annotationClass, projectScope)
                    .forEach(results::add);
        }

        for (String fqn : SpringAnnotations.SPRING_DATA_BASE_TYPES) {
            PsiClass base = facade.findClass(fqn, GlobalSearchScope.allScope(project));
            if (base == null) continue;
            ClassInheritorsSearch.search(base, projectScope, true).forEach(results::add);
        }

        LOG.info("discoverRepositories: found " + results.size());
        return new ArrayList<>(results);
    }

    /** Must be called inside a read action. */
    private int countCallSites(PsiMethod method) {
        return ReferencesSearch.search(method, GlobalSearchScope.projectScope(project))
                .findAll().size();
    }
}
