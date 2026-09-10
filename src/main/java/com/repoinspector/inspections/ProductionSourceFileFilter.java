package com.repoinspector.inspections;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.GeneratedSourcesFilter;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/** Shared source eligibility policy for every RepoBuddy inspection and scanner entry point. */
public final class ProductionSourceFileFilter {

    private ProductionSourceFileFilter() {}

    /**
     * Returns {@code true} only for production source files when IntelliJ can classify the file.
     * Unindexed/synthetic PSI is allowed unless its path clearly identifies a conventional test
     * source tree; this keeps change-baseline PSI and lightweight fixture files analyzable.
     */
    public static boolean shouldAnalyze(@NotNull PsiFile file) {
        VirtualFile virtualFile = file.getVirtualFile();
        if (virtualFile == null) return true;

        Project project = file.getProject();
        ProjectFileIndex index = ProjectRootManager.getInstance(project).getFileIndex();
        if (index.isInTestSourceContent(virtualFile)) return false;
        if (index.isInLibraryClasses(virtualFile) || index.isInLibrarySource(virtualFile)) return false;
        if (GeneratedSourcesFilter.isGeneratedSourceByAnyFilter(virtualFile, project)) return false;
        if (index.isInSourceContent(virtualFile)) return true;
        if (index.isInContent(virtualFile)) return false;

        return !hasConventionalTestSourcePath(virtualFile.getPath());
    }

    private static boolean hasConventionalTestSourcePath(@NotNull String path) {
        String normalized = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        return normalized.contains("/src/test/java/")
                || normalized.contains("/src/test/kotlin/")
                || normalized.contains("/src/test/resources/");
    }
}
