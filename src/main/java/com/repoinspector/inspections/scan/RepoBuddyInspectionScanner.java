package com.repoinspector.inspections.scan;

import com.intellij.codeInspection.InspectionEngine;
import com.intellij.codeInspection.InspectionManager;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.GlobalInspectionContext;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper;
import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.FileTypeIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.repoinspector.inspections.MissingPaginationInspection;
import com.repoinspector.inspections.MissingTransactionalInspection;
import com.repoinspector.inspections.NPlusOneQueryInspection;
import com.repoinspector.inspections.SelfInvocationInspection;
import com.repoinspector.inspections.UnsafeQueryInspection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

/**
 * Runs the RepoBuddy {@link LocalInspectionTool}s programmatically over a scope and collects
 * their findings, so the tool window can show exactly what the editor reports — the same
 * inspection classes are reused via {@link InspectionEngine}, never reimplemented.
 *
 * <p>All PSI access happens inside a {@link ReadAction}; callers should invoke {@link #scan}
 * off the EDT (e.g. a pooled thread) in smart mode.
 */
public final class RepoBuddyInspectionScanner {

    /** A single inspection hit, flattened for table display and navigation. */
    public record Finding(@NotNull String inspection, @NotNull String fileName, @NotNull String filePath,
                          int line, @NotNull String message,
                          @Nullable VirtualFile file, int offset) {}

    private record NamedInspection(String label, Supplier<LocalInspectionTool> factory) {}

    // Constructed with alwaysAnalyze = true so the panel/indicators get findings even when
    // panel-only mode disables these inspections in the editor daemon.
    private static final List<NamedInspection> INSPECTIONS = List.of(
            new NamedInspection("Unsafe @Query", () -> new UnsafeQueryInspection(true)),
            new NamedInspection("Missing pagination", () -> new MissingPaginationInspection(true)),
            new NamedInspection("Missing @Transactional", () -> new MissingTransactionalInspection(true)),
            new NamedInspection("N+1 query", () -> new NPlusOneQueryInspection(true)),
            new NamedInspection("@Transactional self-invocation", () -> new SelfInvocationInspection(true))
    );

    /** Scans all Java files in {@code scope}; returns findings ordered by file then line. */
    public @NotNull List<Finding> scan(@NotNull Project project, @NotNull GlobalSearchScope scope) {
        return ReadAction.compute(() -> {
            List<Finding> findings = new ArrayList<>();
            InspectionManager manager = InspectionManager.getInstance(project);
            GlobalInspectionContext context = manager.createNewGlobalContext();
            PsiManager psiManager = PsiManager.getInstance(project);

            Collection<VirtualFile> files = FileTypeIndex.getFiles(JavaFileType.INSTANCE, scope);
            for (VirtualFile vf : files) {
                PsiFile psiFile = psiManager.findFile(vf);
                if (psiFile != null) collectFromFile(psiFile, context, findings);
            }
            findings.sort((a, b) -> {
                int byPath = a.filePath().compareToIgnoreCase(b.filePath());
                return byPath != 0 ? byPath : Integer.compare(a.line(), b.line());
            });
            return findings;
        });
    }

    /** Scans a single already-open file (cheap, for the "current file" scope). */
    public @NotNull List<Finding> scanFile(@NotNull Project project, @NotNull PsiFile psiFile) {
        return ReadAction.compute(() -> {
            List<Finding> findings = new ArrayList<>();
            GlobalInspectionContext context = InspectionManager.getInstance(project).createNewGlobalContext();
            collectFromFile(psiFile, context, findings);
            findings.sort((a, b) -> Integer.compare(a.line(), b.line()));
            return findings;
        });
    }

    private void collectFromFile(@NotNull PsiFile psiFile, @NotNull GlobalInspectionContext context,
                                 @NotNull List<Finding> sink) {
        VirtualFile vf = psiFile.getVirtualFile();
        String fileName = psiFile.getName();
        String filePath = vf != null ? vf.getPath() : fileName;

        for (NamedInspection ni : INSPECTIONS) {
            LocalInspectionToolWrapper wrapper = new LocalInspectionToolWrapper(ni.factory().get());
            List<ProblemDescriptor> problems = InspectionEngine.runInspectionOnFile(psiFile, wrapper, context);
            for (ProblemDescriptor descriptor : problems) {
                PsiElement element = descriptor.getPsiElement();
                int offset = element != null ? startOffset(element) : 0;
                sink.add(new Finding(
                        ni.label(), fileName, filePath,
                        descriptor.getLineNumber() + 1,
                        stripTags(descriptor.getDescriptionTemplate()),
                        vf, offset));
            }
        }
    }

    private static int startOffset(@NotNull PsiElement element) {
        TextRange range = element.getTextRange();
        return range != null ? range.getStartOffset() : element.getTextOffset();
    }

    /** Inspection messages here are plain text, but defensively strip any HTML tags. */
    private static @NotNull String stripTags(@Nullable String message) {
        if (message == null) return "";
        return message.replaceAll("<[^>]+>", "").trim();
    }
}
