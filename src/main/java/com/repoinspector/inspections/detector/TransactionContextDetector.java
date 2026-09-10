package com.repoinspector.inspections.detector;

import com.intellij.psi.JavaRecursiveElementVisitor;
import com.intellij.psi.PsiAssignmentExpression;
import com.intellij.psi.PsiBlockStatement;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiDeclarationStatement;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiExpressionStatement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiIfStatement;
import com.intellij.psi.PsiLambdaExpression;
import com.intellij.psi.PsiLocalVariable;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiMethodReferenceExpression;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiNewExpression;
import com.intellij.psi.PsiParenthesizedExpression;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiReturnStatement;
import com.intellij.psi.PsiStatement;
import com.intellij.psi.PsiThrowStatement;
import com.intellij.psi.PsiTryStatement;
import com.intellij.psi.PsiTypeCastExpression;
import com.intellij.psi.PsiVariable;
import com.repoinspector.constants.SpringAnnotations;
import com.repoinspector.inspections.ProductionSourceFileFilter;
import com.repoinspector.model.OperationType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Bounded execution-context analysis used by the missing-transaction inspection.
 * Callback declarations are deliberately inert; callback bodies are entered only at a
 * supported imperative or reactive invocation boundary.
 */
public final class TransactionContextDetector {

    public enum WritePath { DIRECT_DATA, DIRECT_REPOSITORY, HELPER }
    public enum ContextKind { NONE, IMPERATIVE, REACTIVE }

    private static final int MAX_CALLS = 20;

    private final boolean includeRepositoryWrites;
    private final boolean analyzeCalledMethods;
    private int followedCalls;
    private final Set<MethodState> visited = new HashSet<>();

    public TransactionContextDetector(boolean includeRepositoryWrites, boolean analyzeCalledMethods) {
        this.includeRepositoryWrites = includeRepositoryWrites;
        this.analyzeCalledMethods = analyzeCalledMethods;
    }

    public @NotNull EnumSet<WritePath> analyze(@NotNull PsiMethod entry) {
        followedCalls = 0;
        visited.clear();
        visited.add(new MethodState(entry, ContextKind.NONE));
        PsiCodeBlock body = entry.getBody();
        if (body == null) return EnumSet.noneOf(WritePath.class);
        return scanBlock(body, new Context(ContextKind.NONE, entry), entry, false);
    }

    private @NotNull EnumSet<WritePath> scanBlock(@NotNull PsiCodeBlock block,
                                                  @NotNull Context context,
                                                  @NotNull PsiMethod entry,
                                                  boolean helper) {
        EnumSet<WritePath> result = EnumSet.noneOf(WritePath.class);
        PsiStatement[] statements = block.getStatements();
        for (int i = 0; i < statements.length; i++) {
            ManagerRegion region = findManagerRegion(statements, i);
            if (region != null) {
                result.addAll(scanStatements(statements, i, region.beginIndex, context, entry, helper));
                Context imperative = new Context(ContextKind.IMPERATIVE, region.beginCall);
                result.addAll(scanStatements(statements, region.beginIndex + 1,
                        region.endExclusive, imperative, entry, helper));
                i = region.endExclusive - 1;
            } else {
                result.addAll(scanElement(statements[i], context, entry, helper));
            }
        }
        return result;
    }

    private @NotNull EnumSet<WritePath> scanStatements(PsiStatement[] statements, int from, int to,
                                                        Context context, PsiMethod entry, boolean helper) {
        EnumSet<WritePath> result = EnumSet.noneOf(WritePath.class);
        for (int i = from; i < to; i++) result.addAll(scanElement(statements[i], context, entry, helper));
        return result;
    }

    private @NotNull EnumSet<WritePath> scanElement(@Nullable PsiElement element,
                                                    @NotNull Context context,
                                                    @NotNull PsiMethod entry,
                                                    boolean helper) {
        EnumSet<WritePath> result = EnumSet.noneOf(WritePath.class);
        if (element == null) return result;
        element.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitCodeBlock(@NotNull PsiCodeBlock block) {
                result.addAll(scanBlock(block, context, entry, helper));
            }

            @Override
            public void visitLambdaExpression(@NotNull PsiLambdaExpression expression) {
                // A lambda declaration does not execute its body.
            }

            @Override
            public void visitDeclarationStatement(@NotNull PsiDeclarationStatement statement) {
                for (PsiElement declared : statement.getDeclaredElements()) {
                    if (declared instanceof PsiVariable variable
                            && ProgrammaticTransactionSignals.isPublisherType(variable.getType())) {
                        // Reactive publisher assembly is deferred until its execution context is known.
                        continue;
                    }
                    declared.accept(this);
                }
            }

            @Override
            public void visitReturnStatement(@NotNull PsiReturnStatement statement) {
                PsiExpression value = statement.getReturnValue();
                if (value != null && ProgrammaticTransactionSignals.isPublisherType(value.getType())) {
                    PublisherResolution resolution = resolvePublisher(value, statement);
                    Context effective = resolution.certain ? context
                            : new Context(ContextKind.NONE, statement);
                    for (PsiExpression publisher : resolution.expressions) {
                        result.addAll(scanElement(publisher, effective, entry, helper));
                    }
                    return;
                }
                super.visitReturnStatement(statement);
            }

            @Override
            public void visitClass(@NotNull PsiClass aClass) {
                // Local and anonymous callback method bodies are deferred too.
            }

            @Override
            public void visitMethodCallExpression(@NotNull PsiMethodCallExpression call) {
                PsiExpression qualifier = call.getMethodExpression().getQualifierExpression();

                if (ProgrammaticTransactionSignals.isTransactionCallback(call)) {
                    if (qualifier != null) qualifier.accept(this);
                    scanCallbackArguments(call, ContextKind.IMPERATIVE);
                    return;
                }
                if (ProgrammaticTransactionSignals.isReactiveExecute(call)) {
                    if (qualifier != null) qualifier.accept(this);
                    scanCallbackArguments(call, ContextKind.REACTIVE);
                    return;
                }
                if (ProgrammaticTransactionSignals.isReactiveTransactional(call)) {
                    if (qualifier != null) qualifier.accept(this);
                    for (PsiExpression argument : call.getArgumentList().getExpressions()) {
                        scanPublisher(argument, call);
                    }
                    return;
                }
                if (ProgrammaticTransactionSignals.isReactiveAsBoundary(call)) {
                    scanPublisher(qualifier, call);
                    return;
                }

                if (qualifier != null) qualifier.accept(this);
                for (PsiExpression argument : call.getArgumentList().getExpressions()) {
                    CallbackResolution callback = resolveCallback(argument, call, new HashSet<>());
                    if (callback.targets.isEmpty()) {
                        argument.accept(this);
                    } else {
                        Context unproven = new Context(ContextKind.NONE, call);
                        for (PsiElement target : callback.targets) {
                            result.addAll(scanCallbackTarget(target, unproven, entry));
                        }
                    }
                }

                PsiMethod callee = call.resolveMethod();
                if (context.kind == ContextKind.NONE) {
                    if (isDataWrite(call, callee)) result.add(WritePath.DIRECT_DATA);
                    if (includeRepositoryWrites && isRepositoryWrite(call, callee)) {
                        result.add(WritePath.DIRECT_REPOSITORY);
                    }
                }

                if (analyzeCalledMethods && isEligibleProjectMethod(callee)) {
                    Context calleeContext = contextForCall(call, callee, context);
                    EnumSet<WritePath> called = scanMethod(callee, calleeContext, entry);
                    if (!called.isEmpty()) result.add(WritePath.HELPER);
                }
            }

            private void scanCallbackArguments(PsiMethodCallExpression call, ContextKind kind) {
                Context callbackContext = new Context(kind, call);
                for (PsiExpression argument : call.getArgumentList().getExpressions()) {
                    CallbackResolution resolution = resolveCallback(argument, call, new HashSet<>());
                    Context effective = resolution.certain ? callbackContext
                            : new Context(ContextKind.NONE, call);
                    if (resolution.targets.isEmpty()) {
                        result.addAll(scanElement(argument, effective, entry, helper));
                    } else {
                        for (PsiElement target : resolution.targets) {
                            result.addAll(scanCallbackTarget(target, effective, entry));
                        }
                    }
                }
            }

            private void scanPublisher(PsiExpression expression, PsiMethodCallExpression call) {
                PublisherResolution resolution = resolvePublisher(expression, call);
                Context effective = new Context(resolution.certain ? ContextKind.REACTIVE : ContextKind.NONE, call);
                for (PsiExpression publisher : resolution.expressions) {
                    result.addAll(scanElement(publisher, effective, entry, helper));
                }
            }
        });
        return result;
    }

    private @NotNull EnumSet<WritePath> scanCallbackTarget(@NotNull PsiElement target,
                                                            @NotNull Context context,
                                                            @NotNull PsiMethod entry) {
        if (target instanceof PsiLambdaExpression lambda) {
            return scanElement(lambda.getBody(), context, entry, false);
        }
        if (target instanceof PsiMethod method) return scanMethod(method, context, entry);
        if (target instanceof PsiCodeBlock block) return scanBlock(block, context, entry, false);
        return scanElement(target, context, entry, false);
    }

    private @NotNull EnumSet<WritePath> scanMethod(@NotNull PsiMethod method,
                                                   @NotNull Context context,
                                                   @NotNull PsiMethod entry) {
        if (followedCalls >= MAX_CALLS) return EnumSet.noneOf(WritePath.class);
        MethodState state = new MethodState(method, context.kind);
        if (!visited.add(state)) return EnumSet.noneOf(WritePath.class);
        followedCalls++;
        PsiCodeBlock body = method.getBody();
        if (body == null) return EnumSet.noneOf(WritePath.class);
        EnumSet<WritePath> writes = scanBlock(body, context, entry, true);
        if (writes.isEmpty()) return writes;
        return EnumSet.of(WritePath.HELPER);
    }

    private @NotNull Context contextForCall(@NotNull PsiMethodCallExpression call,
                                            @NotNull PsiMethod callee,
                                            @NotNull Context current) {
        if (!isTransactional(callee)) return current;
        if (isSameInstanceCall(call, callee)) return current;
        if (callee.hasModifierProperty(PsiModifier.STATIC)
                || callee.hasModifierProperty(PsiModifier.PRIVATE)) return current;
        PsiExpression qualifier = unwrap(call.getMethodExpression().getQualifierExpression());
        if (!(qualifier instanceof PsiReferenceExpression reference)
                || !(reference.resolve() instanceof PsiField || reference.resolve() instanceof PsiParameter)) {
            return current;
        }
        return new Context(ContextKind.IMPERATIVE, call);
    }

    private static boolean isSameInstanceCall(PsiMethodCallExpression call, PsiMethod callee) {
        PsiExpression qualifier = call.getMethodExpression().getQualifierExpression();
        if (qualifier == null) return true;
        if (qualifier instanceof PsiReferenceExpression reference) {
            String text = reference.getReferenceName();
            if ("this".equals(text) || "super".equals(text)) return true;
        }
        PsiClass callerClass = containingClass(call);
        return callerClass != null && callerClass.equals(callee.getContainingClass())
                && qualifier.getText().startsWith("this.");
    }

    private static @Nullable PsiClass containingClass(PsiElement element) {
        for (PsiElement parent = element.getParent(); parent != null; parent = parent.getParent()) {
            if (parent instanceof PsiClass psiClass) return psiClass;
        }
        return null;
    }

    private static boolean isEligibleProjectMethod(@Nullable PsiMethod method) {
        if (method == null || method.getBody() == null || method.getContainingFile() == null) return false;
        PsiClass owner = method.getContainingClass();
        if (owner == null || DataAccessCalls.isSpringDataRepository(owner)) return false;
        return ProductionSourceFileFilter.shouldAnalyze(method.getContainingFile());
    }

    private static boolean isTransactional(@NotNull PsiMethod method) {
        for (String fqn : SpringAnnotations.TRANSACTIONAL_FQNS) {
            if (method.hasAnnotation(fqn)) return true;
        }
        PsiClass owner = method.getContainingClass();
        if (owner != null) {
            for (String fqn : SpringAnnotations.TRANSACTIONAL_FQNS) {
                if (owner.hasAnnotation(fqn)) return true;
            }
        }
        return false;
    }

    private static @NotNull CallbackResolution resolveCallback(@Nullable PsiExpression expression,
                                                                @NotNull PsiElement invocation,
                                                                @NotNull Set<PsiElement> resolving) {
        expression = unwrap(expression);
        if (expression == null || !resolving.add(expression)) return CallbackResolution.ambiguous();
        if (expression instanceof PsiLambdaExpression lambda) return CallbackResolution.certain(lambda);
        if (expression instanceof PsiMethodReferenceExpression reference) {
            PsiElement resolved = reference.resolve();
            return resolved instanceof PsiMethod method ? CallbackResolution.certain(method)
                    : CallbackResolution.ambiguous();
        }
        if (expression instanceof PsiNewExpression creation && creation.getAnonymousClass() != null) {
            List<PsiElement> bodies = new ArrayList<>();
            for (PsiMethod method : creation.getAnonymousClass().getMethods()) {
                if (!method.isConstructor() && method.getBody() != null) bodies.add(method);
            }
            return bodies.size() == 1 ? new CallbackResolution(bodies, true)
                    : new CallbackResolution(bodies, false);
        }
        if (expression instanceof PsiReferenceExpression reference
                && reference.resolve() instanceof PsiVariable variable) {
            return resolveVariable(variable, invocation, resolving);
        }
        if (expression instanceof PsiMethodCallExpression factory && isEligibleProjectMethod(factory.resolveMethod())) {
            List<PsiElement> targets = new ArrayList<>();
            boolean certain = true;
            ReturnCollector collector = new ReturnCollector();
            factory.resolveMethod().getBody().accept(collector);
            if (collector.returns.isEmpty()) return CallbackResolution.ambiguous();
            for (PsiExpression returned : collector.returns) {
                CallbackResolution nested = resolveCallback(returned, factory, resolving);
                targets.addAll(nested.targets);
                certain &= nested.certain && !nested.targets.isEmpty();
            }
            return new CallbackResolution(targets, certain);
        }
        return CallbackResolution.ambiguous();
    }

    private static @NotNull CallbackResolution resolveVariable(@NotNull PsiVariable variable,
                                                                @NotNull PsiElement invocation,
                                                                @NotNull Set<PsiElement> resolving) {
        List<PsiExpression> values = new ArrayList<>();
        if (variable.getInitializer() != null) values.add(variable.getInitializer());
        PsiMethod scope = containingMethod(invocation);
        if (scope != null && scope.getBody() != null) {
            scope.getBody().accept(new JavaRecursiveElementVisitor() {
                @Override
                public void visitLambdaExpression(@NotNull PsiLambdaExpression expression) { }
                @Override
                public void visitClass(@NotNull PsiClass aClass) { }
                @Override
                public void visitAssignmentExpression(@NotNull PsiAssignmentExpression assignment) {
                    if (assignment.getLExpression() instanceof PsiReferenceExpression reference
                            && reference.resolve() == variable
                            && assignment.getTextOffset() < invocation.getTextOffset()) {
                        values.add(assignment.getRExpression());
                    }
                    super.visitAssignmentExpression(assignment);
                }
            });
        }
        boolean immutable = variable instanceof PsiLocalVariable
                ? values.size() == 1
                : variable instanceof PsiField && variable.hasModifierProperty(PsiModifier.FINAL)
                && values.size() == 1;
        List<PsiElement> targets = new ArrayList<>();
        boolean certain = immutable;
        for (PsiExpression value : values) {
            CallbackResolution nested = resolveCallback(value, invocation, resolving);
            targets.addAll(nested.targets);
            certain &= nested.certain;
        }
        return new CallbackResolution(targets, certain && !targets.isEmpty());
    }

    private static @Nullable PsiMethod containingMethod(PsiElement element) {
        for (PsiElement parent = element; parent != null; parent = parent.getParent()) {
            if (parent instanceof PsiMethod method) return method;
        }
        return null;
    }

    private static @Nullable PsiExpression unwrap(@Nullable PsiExpression expression) {
        while (true) {
            if (expression instanceof PsiParenthesizedExpression parenthesized) {
                expression = parenthesized.getExpression();
            } else if (expression instanceof PsiTypeCastExpression cast) {
                expression = cast.getOperand();
            } else return expression;
        }
    }

    private static @NotNull PublisherResolution resolvePublisher(@Nullable PsiExpression expression,
                                                                 @NotNull PsiElement invocation) {
        expression = unwrap(expression);
        if (expression instanceof PsiReferenceExpression reference
                && reference.resolve() instanceof PsiVariable variable
                && ProgrammaticTransactionSignals.isPublisherType(variable.getType())) {
            List<PsiExpression> values = variableValues(variable, invocation);
            boolean immutable = variable instanceof PsiLocalVariable ? values.size() == 1
                    : variable instanceof PsiField && variable.hasModifierProperty(PsiModifier.FINAL)
                    && values.size() == 1;
            return new PublisherResolution(values, immutable && !values.isEmpty());
        }
        return expression == null ? new PublisherResolution(List.of(), false)
                : new PublisherResolution(List.of(expression), true);
    }

    private static @NotNull List<PsiExpression> variableValues(@NotNull PsiVariable variable,
                                                               @NotNull PsiElement invocation) {
        List<PsiExpression> values = new ArrayList<>();
        if (variable.getInitializer() != null) values.add(variable.getInitializer());
        PsiMethod scope = containingMethod(invocation);
        if (scope != null && scope.getBody() != null) {
            scope.getBody().accept(new JavaRecursiveElementVisitor() {
                @Override public void visitLambdaExpression(@NotNull PsiLambdaExpression expression) { }
                @Override public void visitClass(@NotNull PsiClass aClass) { }
                @Override public void visitAssignmentExpression(@NotNull PsiAssignmentExpression assignment) {
                    if (assignment.getLExpression() instanceof PsiReferenceExpression reference
                            && reference.resolve() == variable
                            && assignment.getTextOffset() < invocation.getTextOffset()
                            && assignment.getRExpression() != null) {
                        values.add(assignment.getRExpression());
                    }
                    super.visitAssignmentExpression(assignment);
                }
            });
        }
        return values;
    }

    /** Finds a conservative lexical or try/catch manager region beginning at {@code from}. */
    private static @Nullable ManagerRegion findManagerRegion(PsiStatement[] statements, int from) {
        if (from >= statements.length) return null;
        Begin begin = managerBegin(statements[from]);
        if (begin == null) return null;
        if (from + 1 < statements.length && statements[from + 1] instanceof PsiTryStatement tryStatement
                && completedTry(tryStatement, begin)) {
            return new ManagerRegion(from, from + 2, begin.call);
        }
        for (int i = from + 1; i < statements.length; i++) {
            if (containsUnclearControl(statements[i])) return null;
            if (containsManagerBegin(statements[i])) return null;
            if (matchingCompletion(statements[i], begin)) return new ManagerRegion(from, i + 1, begin.call);
        }
        return null;
    }

    private static @Nullable Begin managerBegin(PsiStatement statement) {
        if (!(statement instanceof PsiDeclarationStatement declaration)) return null;
        PsiElement[] declared = declaration.getDeclaredElements();
        if (declared.length != 1 || !(declared[0] instanceof PsiLocalVariable status)) return null;
        if (!(unwrap(status.getInitializer()) instanceof PsiMethodCallExpression call)
                || !ProgrammaticTransactionSignals.isManagerCall(call, "getTransaction")) return null;
        PsiVariable manager = qualifierVariable(call);
        return manager == null ? null : new Begin(status, manager, call);
    }

    private static boolean completedTry(PsiTryStatement statement, Begin begin) {
        PsiCodeBlock tryBlock = statement.getTryBlock();
        if (tryBlock == null || !endsWithCompletion(tryBlock, begin, "commit")) return false;
        PsiCodeBlock[] catches = statement.getCatchBlocks();
        if (catches.length == 0) return false;
        for (PsiCodeBlock block : catches) {
            if (!endsWithCompletionBeforeOptionalThrow(block, begin, "rollback")) return false;
        }
        return statement.getFinallyBlock() == null;
    }

    private static boolean endsWithCompletion(PsiCodeBlock block, Begin begin, String name) {
        PsiStatement[] statements = block.getStatements();
        return statements.length > 0 && matchingCompletion(statements[statements.length - 1], begin, name);
    }

    private static boolean endsWithCompletionBeforeOptionalThrow(PsiCodeBlock block, Begin begin, String name) {
        PsiStatement[] statements = block.getStatements();
        if (statements.length == 0) return false;
        int last = statements.length - 1;
        if (statements[last] instanceof PsiThrowStatement) last--;
        return last >= 0 && matchingCompletion(statements[last], begin, name);
    }

    private static boolean matchingCompletion(PsiStatement statement, Begin begin) {
        return matchingCompletion(statement, begin, "commit")
                || matchingCompletion(statement, begin, "rollback");
    }

    private static boolean matchingCompletion(PsiStatement statement, Begin begin, String name) {
        if (!(statement instanceof PsiExpressionStatement expressionStatement)
                || !(unwrap(expressionStatement.getExpression()) instanceof PsiMethodCallExpression call)
                || !ProgrammaticTransactionSignals.isManagerCall(call, name)) return false;
        if (qualifierVariable(call) != begin.manager) return false;
        PsiExpression[] args = call.getArgumentList().getExpressions();
        return args.length == 1 && unwrap(args[0]) instanceof PsiReferenceExpression reference
                && reference.resolve() == begin.status;
    }

    private static @Nullable PsiVariable qualifierVariable(PsiMethodCallExpression call) {
        PsiExpression qualifier = unwrap(call.getMethodExpression().getQualifierExpression());
        return qualifier instanceof PsiReferenceExpression reference
                && reference.resolve() instanceof PsiVariable variable ? variable : null;
    }

    private static boolean containsUnclearControl(PsiStatement statement) {
        return statement instanceof PsiIfStatement || statement instanceof PsiTryStatement
                || statement instanceof PsiReturnStatement || statement instanceof PsiThrowStatement;
    }

    private static boolean containsManagerBegin(PsiStatement statement) {
        boolean[] found = {false};
        statement.accept(new JavaRecursiveElementVisitor() {
            @Override public void visitMethodCallExpression(@NotNull PsiMethodCallExpression call) {
                if (ProgrammaticTransactionSignals.isManagerCall(call, "getTransaction")) found[0] = true;
                else super.visitMethodCallExpression(call);
            }
        });
        return found[0];
    }

    private static boolean isDataWrite(@NotNull PsiMethodCallExpression call, @Nullable PsiMethod resolved) {
        if (resolved == null) return false;
        String name = call.getMethodExpression().getReferenceName();
        PsiClass owner = resolved.getContainingClass();
        if (name == null || owner == null) return false;
        String fqn = owner.getQualifiedName();
        return (TransactionWriteSignals.isJpaWriteMethod(name)
                && TransactionWriteSignals.isJpaPersistenceType(fqn))
                || (TransactionWriteSignals.isHibernateWriteMethod(name)
                && TransactionWriteSignals.isHibernateSessionType(fqn))
                || (TransactionWriteSignals.isJdbcWriteMethod(name)
                && TransactionWriteSignals.isJdbcTemplateType(fqn));
    }

    private static boolean isRepositoryWrite(@NotNull PsiMethodCallExpression call, @Nullable PsiMethod resolved) {
        if (resolved == null) return false;
        String name = call.getMethodExpression().getReferenceName();
        PsiClass owner = resolved.getContainingClass();
        return name != null && owner != null && DataAccessCalls.isSpringDataRepository(owner)
                && (resolved.hasAnnotation(SpringAnnotations.MODIFYING)
                || OperationType.fromMethodName(name) == OperationType.WRITE);
    }

    private record Context(@NotNull ContextKind kind, @NotNull PsiElement origin) {}
    private record MethodState(@NotNull PsiMethod method, @NotNull ContextKind context) {}
    private record Begin(@NotNull PsiVariable status, @NotNull PsiVariable manager,
                         @NotNull PsiMethodCallExpression call) {}
    private record ManagerRegion(int beginIndex, int endExclusive,
                                 @NotNull PsiMethodCallExpression beginCall) {}
    private record CallbackResolution(@NotNull List<PsiElement> targets, boolean certain) {
        private static CallbackResolution certain(PsiElement target) {
            return new CallbackResolution(List.of(target), true);
        }
        private static CallbackResolution ambiguous() {
            return new CallbackResolution(List.of(), false);
        }
    }
    private record PublisherResolution(@NotNull List<PsiExpression> expressions, boolean certain) {}

    private static final class ReturnCollector extends JavaRecursiveElementVisitor {
        private final List<PsiExpression> returns = new ArrayList<>();
        @Override public void visitLambdaExpression(@NotNull PsiLambdaExpression expression) { }
        @Override public void visitClass(@NotNull PsiClass aClass) { }
        @Override public void visitReturnStatement(@NotNull PsiReturnStatement statement) {
            if (statement.getReturnValue() != null) returns.add(statement.getReturnValue());
        }
    }
}
