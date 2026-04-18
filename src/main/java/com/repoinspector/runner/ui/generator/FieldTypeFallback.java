package com.repoinspector.runner.ui.generator;

import com.repoinspector.runner.model.ParameterDef;
import com.repoinspector.runner.service.ParameterTypeClassifier;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * {@link GenerationStrategy} that always matches and produces a value based on the
 * broad {@link ParameterDef.FieldType} category returned by
 * {@link ParameterTypeClassifier}.
 *
 * <p>This strategy is designed to be the final entry in the strategy chain — it never
 * returns {@code null} and therefore guarantees that {@link #supports} is always
 * {@code true}.
 *
 * @since 1.0
 */
public final class FieldTypeFallback implements GenerationStrategy {

    /** Always returns {@code true}; this strategy is the unconditional fallback. */
    @Override
    public boolean supports(@NotNull ParameterDef param) {
        return true;
    }

    @Override
    @NotNull
    public String generate(@NotNull ParameterDef param) {
        ParameterDef.FieldType fieldType = ParameterTypeClassifier.classify(param.typeName());
        return switch (fieldType) {
            case BOOLEAN -> rnd().nextBoolean() ? "true" : "false";
            case NUMBER  -> String.valueOf(rnd().nextInt(DataPool.MAX_ID - 1) + 1);
            case DECIMAL -> String.format(Locale.ROOT, "%.2f", rnd().nextDouble() * 100);
            case TEXT    -> ValueBuilders.randomFullName();
            case JSON    -> "{}";
        };
    }

    private static ThreadLocalRandom rnd() {
        return ThreadLocalRandom.current();
    }
}
