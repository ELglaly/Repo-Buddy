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
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiNameIdentifierOwner;
import com.intellij.psi.PsiReference;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.search.FileTypeIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.repoinspector.inspections.MissingPaginationInspection;
import com.repoinspector.inspections.MissingTransactionalInspection;
import com.repoinspector.inspections.NPlusOneQueryInspection;
import com.repoinspector.inspections.SelfInvocationInspection;
import com.repoinspector.inspections.UnsafeQueryInspection;
import com.repoinspector.core.RepoBuddyRule;
import com.repoinspector.core.RepoBuddyRules;
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
    public record Finding(@NotNull String ruleId, @NotNull String inspection,
                          @NotNull String fileName, @NotNull String filePath,
                          int line, int column, @NotNull String message, @NotNull String stableAnchor,
                          @NotNull String enclosingClass, @NotNull String enclosingMethod,
                          @NotNull String affectedSymbol, int symbolStartLine, int symbolEndLine,
                          @Nullable VirtualFile file, int offset) {}

    private record NamedInspection(RepoBuddyRule rule, Supplier<LocalInspectionTool> factory) {}

    // Constructed with alwaysAnalyze = true so the panel/indicators get findings even when
    // panel-only mode disables these inspections in the editor daemon.
    private static final List<NamedInspection> INSPECTIONS = List.of(
            new NamedInspection(RepoBuddyRules.UNSAFE_QUERY, () -> new UnsafeQueryInspection(true)),
            new NamedInspection(RepoBuddyRules.MISSING_PAGINATION, () -> new MissingPaginationInspection(true)),
            new NamedInspection(RepoBuddyRules.MISSING_TRANSACTIONAL, () -> new MissingTransactionalInspection(true)),
            new NamedInspection(RepoBuddyRules.N_PLUS_ONE, () -> new NPlusOneQueryInspection(true)),
            new NamedInspection(RepoBuddyRules.SELF_INVOCATION, () -> new SelfInvocationInspection(true))
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
                ProgressManager.checkCanceled();
                if (project.isDisposed()) break;
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
                        ni.rule().id(), ni.rule().name(), fileName, filePath,
                        descriptor.getLineNumber() + 1,
                        columnOf(psiFile, offset),
                        stripTags(descriptor.getDescriptionTemplate()),
                        stableAnchor(element),
                        enclosingClass(element), enclosingMethod(element), affectedSymbol(element),
                        symbolStartLine(psiFile, element), symbolEndLine(psiFile, element),
                        vf, offset));
            }
        }
    }

    private static int startOffset(@NotNull PsiElement element) {
        TextRange range = element.getTextRange();
        return range != null ? range.getStartOffset() : element.getTextOffset();
    }

    private static int columnOf(@NotNull PsiFile file, int offset) {
        var document = PsiDocumentManager.getInstance(file.getProject()).getDocument(file);
        if (document == null || offset < 0 || offset > document.getTextLength()) return 1;
        int line = document.getLineNumber(offset);
        return offset - document.getLineStartOffset(line) + 1;
    }

    private static @NotNull String stableAnchor(@Nullable PsiElement element) {
        if (element == null) return "";
        return String.join("|", enclosingClass(element), enclosingMethod(element),
                affectedSymbol(element), normalizeElementText(element));
    }

    private static @NotNull String enclosingClass(@Nullable PsiElement element) {
        PsiClass owner = element == null ? null : PsiTreeUtil.getParentOfType(element, PsiClass.class, false);
        if (owner == null) return "";
        return owner.getQualifiedName() == null ? String.valueOf(owner.getName()) : owner.getQualifiedName();
    }

    private static @NotNull String enclosingMethod(@Nullable PsiElement element) {
        PsiMethod method = element == null ? null : PsiTreeUtil.getParentOfType(element, PsiMethod.class, false);
        if (method == null) return "";
        String params = java.util.Arrays.stream(method.getParameterList().getParameters())
                .map(parameter -> parameter.getType().getCanonicalText()).collect(java.util.stream.Collectors.joining(","));
        return method.getName() + "(" + params + ")";
    }

    private static @NotNull String affectedSymbol(@Nullable PsiElement element) {
        if (element == null) return "";
        PsiReference reference = element.getReference();
        if (reference == null && element.getParent() instanceof PsiReferenceExpression expression) reference = expression;
        PsiElement resolved = reference == null ? null : reference.resolve();
        if (resolved instanceof PsiMethod method) {
            PsiClass owner = method.getContainingClass();
            return (owner == null || owner.getQualifiedName() == null ? "" : owner.getQualifiedName() + "#")
                    + method.getName() + "/" + method.getParameterList().getParametersCount();
        }
        if (resolved instanceof PsiNameIdentifierOwner named && named.getName() != null) return named.getName();
        if (element instanceof PsiNameIdentifierOwner named && named.getName() != null) return named.getName();
        return normalizeElementText(element);
    }

    private static @NotNull String normalizeElementText(@NotNull PsiElement element) {
        String text = element.getText();
        if (text == null) return element.getClass().getSimpleName();
        text = text.replaceAll("\\s+", " ").trim();
        return text.length() <= 160 ? text : text.substring(0, 160);
    }

    private static int symbolStartLine(@NotNull PsiFile file, @Nullable PsiElement element) {
        PsiMethod method = element == null ? null : PsiTreeUtil.getParentOfType(element, PsiMethod.class, false);
        PsiElement target = method == null ? element : method;
        return lineOf(file, target == null ? 0 : startOffset(target));
    }

    private static int symbolEndLine(@NotNull PsiFile file, @Nullable PsiElement element) {
        PsiMethod method = element == null ? null : PsiTreeUtil.getParentOfType(element, PsiMethod.class, false);
        PsiElement target = method == null ? element : method;
        TextRange range = target == null ? null : target.getTextRange();
        return lineOf(file, range == null ? 0 : Math.max(range.getStartOffset(), range.getEndOffset() - 1));
    }

    private static int lineOf(@NotNull PsiFile file, int offset) {
        var document = PsiDocumentManager.getInstance(file.getProject()).getDocument(file);
        if (document == null || document.getTextLength() == 0) return 1;
        return document.getLineNumber(Math.max(0, Math.min(offset, document.getTextLength() - 1))) + 1;
    }

    /** Inspection messages here are plain text, but defensively strip any HTML tags. */
    private static @NotNull String stripTags(@Nullable String message) {
        if (message == null) return "";
        return message.replaceAll("<[^>]+>", "").trim();
    }
}
