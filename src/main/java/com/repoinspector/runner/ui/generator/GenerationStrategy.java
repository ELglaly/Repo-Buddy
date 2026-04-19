package com.repoinspector.runner.ui.generator;

import com.repoinspector.runner.model.ParameterDef;
import org.jetbrains.annotations.NotNull;

/**
 * Strategy for generating a random sample value for a repository method parameter.
 *
 * <p>Implementations are applied in priority order by
 * {@link com.repoinspector.runner.ui.RandomDataGenerator}. New strategies may be
 * appended to the chain without modifying existing code (OCP).
 *
 * <p>Contract:
 * <ul>
 *   <li>{@link #supports} must never throw and must never return {@code null}.</li>
 *   <li>{@link #generate} is only invoked when {@code supports} returned {@code true}
 *       and must return a non-null, non-empty string.</li>
 * </ul>
 *
 * @since 1.0
 */
public interface GenerationStrategy {

    /**
     * Returns {@code true} if this strategy can produce a value for {@code param}.
     *
     * @param param the parameter descriptor; never {@code null}
     * @return {@code true} when this strategy handles the parameter
     */
    boolean supports(@NotNull ParameterDef param);

    /**
     * Generates a random sample value for the given parameter.
     *
     * <p>Only called when {@link #supports} returned {@code true}.
     *
     * @param param the parameter descriptor; never {@code null}
     * @return a non-null, non-empty sample value string suitable for a UI text widget
     */
    @NotNull
    String generate(@NotNull ParameterDef param);
}
