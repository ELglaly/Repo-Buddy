package com.repoinspector.runner.ui;

import com.repoinspector.runner.model.ParameterDef;
import com.repoinspector.runner.ui.generator.GenerationStrategy;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Thin orchestrator/facade that generates realistic random sample values for
 * repository method parameters.
 *
 * <p>Resolution order for each parameter:
 * <ol>
 *   <li><b>Type-name rules</b> — handled by
 *       {@link com.repoinspector.runner.ui.generator.TypeNameResolver}:
 *       known types such as {@code Pageable}, {@code UUID}, {@code LocalDate},
 *       {@code BigDecimal}, numeric primitives, {@code Sort}, etc.</li>
 *   <li><b>Name-hint rules</b> — handled by
 *       {@link com.repoinspector.runner.ui.generator.NameHintResolver}:
 *       the parameter name is scanned for domain segments like {@code email},
 *       {@code phone}, {@code city}, etc.</li>
 *   <li><b>Field-type fallback</b> — handled by
 *       {@link com.repoinspector.runner.ui.generator.FieldTypeFallback}:
 *       the broad {@link ParameterDef.FieldType} category supplies a sensible default.</li>
 * </ol>
 *
 * <p>This class is an instantiable orchestrator (DIP) that accepts any
 * {@link List} of {@link GenerationStrategy} implementations.  New strategies can be
 * added without modifying this class (OCP).
 *
 * <p>All public methods are thread-safe: the strategy list is immutable after
 * construction and each strategy delegates randomness to {@link java.util.concurrent.ThreadLocalRandom}.
 *
 * @since 1.0
 */
final class RandomDataGenerator {

    /**
     * Shared default instance used by the package-private static {@link #generate} facade.
     * Published safely via the class-loading guarantee on {@code static final} fields.
     */
    private static final RandomDataGenerator DEFAULT = RandomDataGeneratorFactory.createDefault();

    /** Immutable strategy chain evaluated in priority order. */
    private final List<GenerationStrategy> strategies;

    /**
     * Constructs a generator that will apply {@code strategies} in the given order.
     *
     * @param strategies ordered strategy chain; must not be {@code null} or empty
     * @throws IllegalArgumentException if {@code strategies} is empty
     * @since 1.0
     */
    RandomDataGenerator(@NotNull List<GenerationStrategy> strategies) {
        if (strategies.isEmpty()) {
            throw new IllegalArgumentException("At least one GenerationStrategy is required");
        }
        this.strategies = List.copyOf(strategies);
    }

    /**
     * Returns a random sample value for {@code param} as a {@link String} suitable for
     * the corresponding UI input widget.
     *
     * @param param parameter descriptor; never {@code null}
     * @return a non-null, non-empty sample value string
     * @since 1.0
     */
    @NotNull
    String generateValue(@NotNull ParameterDef param) {
        for (GenerationStrategy strategy : strategies) {
            if (strategy.supports(param)) {
                return strategy.generate(param);
            }
        }
        // Unreachable when the chain ends with FieldTypeFallback (supports() always true).
        return "";
    }

    // ── Package-private static facade ────────────────────────────────────────
    // Preserved for backward compatibility with ParameterFormPanel, which calls this
    // as a static method from the same package.

    /**
     * Generates a random sample value for {@code param} using the default strategy chain.
     *
     * <p>This facade delegates to a shared {@link #DEFAULT} instance and is kept
     * package-private to avoid widening the API surface beyond what
     * {@link ParameterFormPanel} requires.
     *
     * @param param parameter descriptor; never {@code null}
     * @return a non-null, non-empty sample value string
     * @since 1.0
     */
    @NotNull
    static String generate(@NotNull ParameterDef param) {
        return DEFAULT.generateValue(param);
    }
}
