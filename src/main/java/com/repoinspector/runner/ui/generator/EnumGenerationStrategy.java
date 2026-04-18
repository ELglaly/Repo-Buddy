package com.repoinspector.runner.ui.generator;

import com.repoinspector.runner.model.ParameterDef;
import org.jetbrains.annotations.NotNull;

/**
 * {@link GenerationStrategy} for enum-typed parameters.
 *
 * <p>When {@link ParameterDef#enumConstants()} is non-empty (populated by
 * {@link com.repoinspector.runner.service.PsiParamExtractor} from the PSI enum class),
 * this strategy returns one of the actual declared enum constant names at random.
 * This guarantees the sample value is always a valid member of the enum — for example,
 * a parameter of type {@code Status} with constants {@code [OPEN, CLOSED]} will only
 * ever produce {@code "OPEN"} or {@code "CLOSED"}, never a random word or integer.
 *
 * <p>This strategy is placed first in the chain so it takes precedence over
 * {@link TypeNameResolver}'s default {@code null} handling for unrecognised types
 * and over {@link FieldTypeFallback}'s generic TEXT fallback.
 *
 * @since 1.0
 */
public final class EnumGenerationStrategy implements GenerationStrategy {

    /**
     * Returns {@code true} when the parameter carries at least one resolved enum constant.
     *
     * @param param the parameter descriptor; never {@code null}
     * @return {@code true} if enum constants are available
     */
    @Override
    public boolean supports(@NotNull ParameterDef param) {
        return !param.enumConstants().isEmpty();
    }

    /**
     * Picks one of the parameter's declared enum constants at random.
     *
     * @param param the parameter descriptor; never {@code null}
     * @return a randomly selected enum constant name; never {@code null} or empty
     */
    @Override
    @NotNull
    public String generate(@NotNull ParameterDef param) {
        return RandomPicker.pick(param.enumConstants());
    }
}
