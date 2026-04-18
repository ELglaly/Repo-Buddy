package com.repoinspector.runner.service;

import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiEnumConstant;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiType;
import com.repoinspector.runner.model.ParameterDef;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared utility for extracting {@link ParameterDef} descriptors from a PSI method.
 *
 * <p>Must be called under a read action (the PSI tree must not be mutated
 * concurrently).  Both {@link com.repoinspector.gutter.RepoMethodGutterProvider}
 * and {@link com.repoinspector.ui.RepoInspectorPanel} delegate here to avoid
 * duplicating parameter-extraction logic (DRY).
 */
public final class PsiParamExtractor {

    private PsiParamExtractor() {}

    /**
     * Returns one {@link ParameterDef} per parameter declared by {@code method},
     * in declaration order.
     *
     * @param method the PSI method — caller must hold the read lock
     * @return immutable list of parameter descriptors; empty for no-arg methods
     */
    public static List<ParameterDef> extract(PsiMethod method) {
        PsiParameter[] params = method.getParameterList().getParameters();
        List<ParameterDef> defs = new ArrayList<>(params.length);
        for (PsiParameter p : params) {
            String typeName = p.getType().getPresentableText();
            // For Class<T> projection params, embed the resolved entity FQN so the
            // generator and agent have a concrete class name to work with.
            if (typeName.equals("Class") || typeName.startsWith("Class<")) {
                String entityFqn = resolveEntityFqn(method);
                typeName = entityFqn != null ? "Class<" + entityFqn + ">" : typeName;
            }
            defs.add(new ParameterDef(p.getName(), typeName, extractEnumConstants(p.getType())));
        }
        return List.copyOf(defs);
    }

    /**
     * Returns an immutable list of declared enum constant names when {@code psiType}
     * resolves to an enum class, or an empty list for every other type.
     *
     * @param psiType the PSI type of the parameter; caller must hold the read lock
     * @return enum constant names in declaration order, or {@link List#of()} for non-enums
     */
    private static List<String> extractEnumConstants(PsiType psiType) {
        if (!(psiType instanceof PsiClassType classType)) return List.of();
        PsiClass psiClass = classType.resolve();
        if (psiClass == null || !psiClass.isEnum()) return List.of();

        List<String> constants = new ArrayList<>();
        for (PsiField field : psiClass.getFields()) {
            if (field instanceof PsiEnumConstant) {
                constants.add(field.getName());
            }
        }
        return List.copyOf(constants);
    }

    /**
     * Walks the repository interface's supertype hierarchy looking for a Spring Data
     * {@code Repository<Entity, ID>} variant and returns the entity's fully-qualified name.
     *
     * <p>Used to provide a concrete default for {@code Class<T>} projection parameters —
     * the entity class itself always works (returns the full entity, no projection applied).
     *
     * @param method any method declared in the repository interface
     * @return FQN of the entity type, or {@code null} when it cannot be resolved
     */
    @Nullable
    private static String resolveEntityFqn(PsiMethod method) {
        PsiClass repoClass = method.getContainingClass();
        if (repoClass == null) return null;
        for (PsiClassType superType : repoClass.getSuperTypes()) {
            PsiClass superClass = superType.resolve();
            if (superClass == null) continue;
            String fqn = superClass.getQualifiedName();
            if (fqn == null) continue;
            if (fqn.startsWith("org.springframework.data") && fqn.contains("Repository")) {
                PsiType[] args = superType.getParameters();
                if (args.length >= 1 && args[0] instanceof PsiClassType entityType) {
                    PsiClass entity = entityType.resolve();
                    if (entity != null) return entity.getQualifiedName();
                }
            }
        }
        return null;
    }

    /**
     * Builds a compact human-readable parameter summary, e.g.
     * {@code "id: Long, name: String"} or {@code "no params"} for zero-arg methods.
     */
    public static String summary(PsiMethod method) {
        PsiParameter[] params = method.getParameterList().getParameters();
        if (params.length == 0) return "no params";
        StringBuilder sb = new StringBuilder();
        for (PsiParameter p : params) {
            if (!sb.isEmpty()) sb.append(", ");
            sb.append(p.getName()).append(": ").append(p.getType().getPresentableText());
        }
        return sb.toString();
    }
}
