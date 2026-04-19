package com.repoinspector.runner.ui.generator;

import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Stateless factory methods that build domain-specific random sample strings.
 *
 * <p>All {@link String#format} calls use {@link Locale#ROOT} to prevent locale-sensitive
 * decimal separators (comma vs. dot) from producing invalid numeric literals in
 * concurrent multi-locale environments.
 *
 * <p>All methods delegate random decisions to {@link ThreadLocalRandom}, which is
 * thread-safe and contention-free.
 *
 * @since 1.0
 */
public final class ValueBuilders {

    /** Alphabet for {@link #randomCode}: uppercase, lowercase, digits. */
    private static final String CODE_CHARS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    private ValueBuilders() {}

    /**
     * Generates a random email address using the {@link DataPool} first-name, last-name,
     * and domain pools.
     *
     * @return a syntactically valid sample email address; never {@code null}
     * @since 1.0
     */
    @NotNull
    public static String randomEmail() {
        String local = RandomPicker.pick(DataPool.FIRST_NAMES).toLowerCase(Locale.ROOT)
                + "." + RandomPicker.pick(DataPool.LAST_NAMES).toLowerCase(Locale.ROOT)
                + rnd().nextInt(99);
        return local + "@" + RandomPicker.pick(DataPool.EMAIL_DOMAINS);
    }

    /**
     * Generates a random full name (first + space + last).
     *
     * @return a sample full name; never {@code null}
     * @since 1.0
     */
    @NotNull
    public static String randomFullName() {
        return RandomPicker.pick(DataPool.FIRST_NAMES) + " " + RandomPicker.pick(DataPool.LAST_NAMES);
    }

    /**
     * Generates a lowercase username with a numeric suffix (e.g. {@code "alice_42"}).
     *
     * @return a sample username; never {@code null}
     * @since 1.0
     */
    @NotNull
    public static String randomUsername() {
        return RandomPicker.pick(DataPool.FIRST_NAMES).toLowerCase(Locale.ROOT)
                + "_" + rnd().nextInt(999);
    }

    /**
     * Generates a random North-American-style phone number.
     *
     * @return a formatted phone string (e.g. {@code "+1-555-012-3456"}); never {@code null}
     * @since 1.0
     */
    @NotNull
    public static String randomPhone() {
        return "+1-" + (200 + rnd().nextInt(800))
                + "-" + String.format(Locale.ROOT, "%03d", rnd().nextInt(1_000))
                + "-" + String.format(Locale.ROOT, "%04d", rnd().nextInt(10_000));
    }

    /**
     * Generates a random street address (house number + Lorem word + "St").
     *
     * @return a sample street address; never {@code null}
     * @since 1.0
     */
    @NotNull
    public static String randomStreet() {
        return (rnd().nextInt(9_999) + 1)
                + " " + capitalise(RandomPicker.pick(DataPool.LOREM_WORDS)) + " St";
    }

    /**
     * Generates a zero-padded five-digit US ZIP code.
     *
     * @return a sample ZIP code string (e.g. {@code "07302"}); never {@code null}
     * @since 1.0
     */
    @NotNull
    public static String randomZip() {
        return String.format(Locale.ROOT, "%05d", rnd().nextInt(100_000));
    }

    /**
     * Generates a Lorem Ipsum sentence of 5–12 words, capitalised, with a trailing period.
     *
     * @return a sample sentence; never {@code null}
     * @since 1.0
     */
    @NotNull
    public static String randomSentence() {
        StringBuilder sb = new StringBuilder();
        int words = rnd().nextInt(8) + 5;
        for (int i = 0; i < words; i++) {
            if (i > 0) sb.append(' ');
            String w = RandomPicker.pick(DataPool.LOREM_WORDS);
            sb.append(i == 0 ? capitalise(w) : w);
        }
        sb.append('.');
        return sb.toString();
    }

    /**
     * Generates a random CSS hex colour string (e.g. {@code "#a3f0c1"}).
     *
     * @return a 7-character hex colour string; never {@code null}
     * @since 1.0
     */
    @NotNull
    public static String randomHexColor() {
        return String.format(Locale.ROOT, "#%06x", rnd().nextInt(0xFF_FF_FF + 1));
    }

    /**
     * Generates a random alphanumeric code of the given length.
     *
     * @param length the desired code length; must be positive
     * @return the generated code string; never {@code null}
     * @throws IllegalArgumentException if {@code length} is not positive
     * @since 1.0
     */
    @NotNull
    public static String randomCode(int length) {
        if (length <= 0) throw new IllegalArgumentException("length must be positive, got: " + length);
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(CODE_CHARS.charAt(rnd().nextInt(CODE_CHARS.length())));
        }
        return sb.toString();
    }

    /**
     * Capitalises the first character of {@code s}, leaving the rest unchanged.
     *
     * @param s input string; must not be {@code null}
     * @return capitalised string; empty string if {@code s} is empty; never {@code null}
     * @since 1.0
     */
    @NotNull
    public static String capitalise(@NotNull String s) {
        if (s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /**
     * Returns the thread-local {@link ThreadLocalRandom} instance.
     *
     * @return never {@code null}
     */
    static ThreadLocalRandom rnd() {
        return ThreadLocalRandom.current();
    }
}
