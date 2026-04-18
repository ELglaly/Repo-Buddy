package com.repoinspector.runner.ui.generator;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Stateless utility for drawing a uniformly random element from an immutable list.
 *
 * <p>Uses {@link ThreadLocalRandom}, which avoids inter-thread contention and is
 * therefore safe to call concurrently without synchronization.
 *
 * @since 1.0
 */
public final class RandomPicker {

    private RandomPicker() {}

    /**
     * Returns a uniformly random element from {@code pool}.
     *
     * @param <T>  element type
     * @param pool source list; must not be {@code null} or empty
     * @return a randomly selected element; never {@code null} when the list contains
     *         non-null values
     * @throws IllegalArgumentException if {@code pool} is empty
     * @since 1.0
     */
    @NotNull
    public static <T> T pick(@NotNull List<T> pool) {
        if (pool.isEmpty()) {
            throw new IllegalArgumentException("Pool must not be empty");
        }
        return pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
    }
}
