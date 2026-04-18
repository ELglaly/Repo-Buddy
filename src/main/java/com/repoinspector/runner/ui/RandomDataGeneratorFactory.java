package com.repoinspector.runner.ui;

import com.repoinspector.runner.ui.generator.EnumGenerationStrategy;
import com.repoinspector.runner.ui.generator.FieldTypeFallback;
import com.repoinspector.runner.ui.generator.NameHintResolver;
import com.repoinspector.runner.ui.generator.TypeNameResolver;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Factory that constructs a {@link RandomDataGenerator} wired with the default
 * strategy chain.
 *
 * <p>The default chain evaluates strategies in this priority order:
 * <ol>
 *   <li>{@link TypeNameResolver} — known Java types (Pageable, UUID, date/time, numerics…)</li>
 *   <li>{@link NameHintResolver} — parameter-name semantic hints (email, city, price…)</li>
 *   <li>{@link FieldTypeFallback} — broad {@link com.repoinspector.runner.model.ParameterDef.FieldType} fallback</li>
 * </ol>
 *
 * <p>Custom strategy chains can be injected directly via
 * {@link RandomDataGenerator#RandomDataGenerator(List)} without touching this factory.
 *
 * @since 1.0
 */
final class RandomDataGeneratorFactory {

    private RandomDataGeneratorFactory() {}

    /**
     * Creates a {@link RandomDataGenerator} with the default strategy chain.
     *
     * @return a fully initialised, thread-safe generator instance; never {@code null}
     * @since 1.0
     */
    @NotNull
    static RandomDataGenerator createDefault() {
        return new RandomDataGenerator(List.of(
                new EnumGenerationStrategy(),
                new TypeNameResolver(),
                new NameHintResolver(),
                new FieldTypeFallback()
        ));
    }
}
