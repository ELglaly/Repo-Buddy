package com.repoinspector.runner.ui.generator;

import com.repoinspector.runner.model.ParameterDef;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * {@link GenerationStrategy} that dispatches on the Java type name.
 *
 * <h3>Pageable / Sort deserialization note</h3>
 * <p>Spring Data's {@code Pageable} is an interface and {@code PageRequest} has no public
 * no-arg constructor, so plain Jackson cannot deserialize either without a registered
 * {@code PageModule} (provided by {@code SpringDataWebAutoConfiguration}).
 *
 * <p>The JSON generated here uses the <em>deserialization</em> format that Spring Data's
 * {@code PageModule} actually reads:
 * <ul>
 *   <li>Pagination fields: {@code "page"} and {@code "size"}.</li>
 *   <li>Sort field: {@code "sort": { "orders": [{...}] }} — the {@code orders} array format,
 *       <b>not</b> the {@code sorted}/{@code unsorted}/{@code empty} boolean format which
 *       is the serialization output and is ignored by the deserializer.</li>
 * </ul>
 *
 * <p>If the running Spring Boot application does not have {@code SpringDataWebAutoConfiguration}
 * on the classpath, the server-side runner must extract the {@code page} and {@code size}
 * values from the JSON and construct {@code PageRequest.of(page, size)} programmatically.
 *
 * <p>{@code Optional<X>} wrappers are unwrapped recursively up to a depth of
 * {@value #MAX_OPTIONAL_DEPTH} to guard against adversarial deeply-nested inputs.
 *
 * <p>{@code String}/{@code java.lang.String} is intentionally not handled here; it is
 * delegated to {@link NameHintResolver} and {@link FieldTypeFallback} so that name-based
 * semantics drive the value.
 *
 * @since 1.0
 */
public final class TypeNameResolver implements GenerationStrategy {

    /**
     * Maximum unwrapping depth for nested {@code Optional<Optional<…>>} wrappers.
     * Beyond this limit the raw type name is treated as unrecognised, preventing a
     * stack overflow on adversarial input.
     */
    private static final int MAX_OPTIONAL_DEPTH = 5;

    /**
     * Reusable JSON fragment for a single ascending-ID sort order.
     * Inlined into both {@code Sort} and {@code Pageable} JSON to keep them consistent.
     */
    private static final String SORT_ORDER_JSON =
            "{\"direction\":\"ASC\",\"property\":\"id\",\"ignoreCase\":false,\"nullHandling\":\"NATIVE\"}";

    /**
     * Reusable sort JSON using the {@code orders} array format that Spring Data's
     * {@code PageModule} deserializer reads.  The {@code sorted}/{@code unsorted}/
     * {@code empty} boolean format is the <em>serialization</em> output and is
     * silently ignored during deserialization.
     */
    private static final String SORT_JSON =
            "{\"orders\":[" + SORT_ORDER_JSON + "]}";

    @Override
    public boolean supports(@NotNull ParameterDef param) {
        return resolve(param.typeName().trim(), param.name().toLowerCase(Locale.ROOT), 0) != null;
    }

    @Override
    @NotNull
    public String generate(@NotNull ParameterDef param) {
        String result = resolve(param.typeName().trim(), param.name().toLowerCase(Locale.ROOT), 0);
        return result != null ? result : "";
    }

    /**
     * Core dispatch from type name to sample value.
     *
     * @param type  the presentable type name (trimmed)
     * @param name  the lowercased parameter name (used by numeric/decimal name refinement)
     * @param depth current {@code Optional} unwrapping depth
     * @return a sample value string, or {@code null} when this strategy does not handle the type
     * @since 1.0
     */
    @Nullable
    private static String resolve(String type, String name, int depth) {
        // Strip Optional<X> wrapper and recurse on the inner type.
        // Depth guard prevents stack overflow on adversarial inputs like Optional<Optional<…>>.
        if (type.startsWith("Optional<") && type.endsWith(">") && depth < MAX_OPTIONAL_DEPTH) {
            String inner = type.substring("Optional<".length(), type.length() - 1);
            return resolve(inner, name, depth + 1);
        }

        // Class<T> / Class<?> — Spring Data projection parameter.
        // PsiParamExtractor resolves T to the repository entity FQN when possible,
        // producing "Class<com.example.User>". Extract and return that FQN directly
        // so the agent can load a real class. Fall back to a placeholder when T could
        // not be resolved (unresolved generic or non-repository context).
        if (type.equals("Class") || type.startsWith("Class<")) {
            if (type.startsWith("Class<") && type.endsWith(">")) {
                String inner = type.substring(6, type.length() - 1).trim();
                if (inner.contains(".") && !inner.equals("?")) return inner;
            }
            return "com.example.YourProjectionInterface";
        }

        return switch (type) {

            // ── Pagination ──────────────────────────────────────────────────────
            // IMPORTANT: Sort JSON uses {"orders":[...]} — the format Spring Data's
            // PageModule deserializer reads.  The {"sorted":…,"unsorted":…} format is
            // serialization-only output and causes InvalidDefinitionException when used
            // for deserialization.
            case "Pageable",
                 "PageRequest",
                 "org.springframework.data.domain.Pageable",
                 "org.springframework.data.domain.PageRequest" ->
                    "{\"page\":0,\"size\":10,\"sort\":" + SORT_JSON + "}";

            case "Sort",
                 "org.springframework.data.domain.Sort" ->
                    SORT_JSON;

            // ── UUID ────────────────────────────────────────────────────────────
            case "UUID",
                 "java.util.UUID" ->
                    UUID.randomUUID().toString();

            // ── BigDecimal / BigInteger / Number ────────────────────────────────
            case "BigDecimal",
                 "java.math.BigDecimal" ->
                    String.format(Locale.ROOT, "%.2f", rnd().nextDouble() * 999 + 1);

            case "BigInteger",
                 "java.math.BigInteger" ->
                    String.valueOf((long) rnd().nextInt(1_000_000) + 1);

            case "Number",
                 "java.lang.Number" ->
                    String.valueOf(rnd().nextInt(10_000) + 1);

            // ── Date / Time ─────────────────────────────────────────────────────
            case "LocalDate",
                 "java.time.LocalDate" ->
                    LocalDate.now()
                            .minusDays(rnd().nextInt(DataPool.DATE_LOOKAHEAD_DAYS))
                            .toString();

            case "LocalTime",
                 "java.time.LocalTime" ->
                    LocalTime.of(rnd().nextInt(24), rnd().nextInt(60), rnd().nextInt(60))
                            .format(DateTimeFormatter.ISO_LOCAL_TIME);

            case "LocalDateTime",
                 "java.time.LocalDateTime" ->
                    LocalDateTime.now()
                            .minusDays(rnd().nextInt(DataPool.DATE_LOOKAHEAD_DAYS))
                            .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);

            case "ZonedDateTime",
                 "java.time.ZonedDateTime",
                 "OffsetDateTime",
                 "java.time.OffsetDateTime" ->
                    LocalDateTime.now()
                            .minusDays(rnd().nextInt(DataPool.DATE_LOOKAHEAD_DAYS))
                            .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "Z";

            // Instant: epoch-second-based ISO-8601 UTC string
            case "Instant",
                 "java.time.Instant" ->
                    Instant.now()
                            .minusSeconds((long) rnd().nextInt(DataPool.DATE_LOOKAHEAD_DAYS) * 86_400L)
                            .toString();

            case "Date",
                 "java.util.Date",
                 "java.sql.Date" ->
                    LocalDate.now()
                            .minusDays(rnd().nextInt(DataPool.DATE_LOOKAHEAD_DAYS))
                            .toString();

            // System.currentTimeMillis() is wall-clock time: not monotonic and may repeat
            // under NTP corrections or DST transitions.
            case "Timestamp",
                 "java.sql.Timestamp" ->
                    String.valueOf(System.currentTimeMillis()
                            - (long) rnd().nextInt(DataPool.MILLIS_PER_DAY) * 30);

            // ── Duration / Period ───────────────────────────────────────────────
            case "Duration",
                 "java.time.Duration" ->
                    "PT" + (rnd().nextInt(23) + 1) + "H" + rnd().nextInt(60) + "M";

            case "Period",
                 "java.time.Period" ->
                    "P" + (rnd().nextInt(2) + 1) + "Y" + rnd().nextInt(12) + "M" + rnd().nextInt(28) + "D";

            // ── Numeric primitives / wrappers ───────────────────────────────────
            case "int",    "Integer", "java.lang.Integer" -> numericByName(name, 1, 10_000);
            case "long",   "Long",    "java.lang.Long"    -> numericByName(name, 1, 100_000);
            case "short",  "Short"                        -> String.valueOf(rnd().nextInt(1_000) + 1);
            case "byte",   "Byte"                         -> String.valueOf(rnd().nextInt(127));
            case "double", "Double"                       -> decimalByName(name);
            case "float",  "Float"                        -> decimalByName(name);

            // ── Boolean ─────────────────────────────────────────────────────────
            case "boolean", "Boolean", "java.lang.Boolean" ->
                    rnd().nextBoolean() ? "true" : "false";

            // String is intentionally omitted: delegated to NameHintResolver and
            // FieldTypeFallback so name-based semantics drive the generated value.

            // ── Common collection literals ──────────────────────────────────────
            case "List<String>",
                 "Set<String>",
                 "Collection<String>",
                 "Iterable<String>" ->
                    "[\"" + RandomPicker.pick(DataPool.LOREM_WORDS)
                    + "\", \"" + RandomPicker.pick(DataPool.LOREM_WORDS) + "\"]";

            case "List<Long>",    "List<Integer>",
                 "Set<Long>",     "Set<Integer>",
                 "Collection<Long>", "Collection<Integer>" ->
                    "[" + (rnd().nextInt(900) + 1) + ", " + (rnd().nextInt(900) + 1) + "]";

            case "List<UUID>", "Set<UUID>", "Collection<UUID>" ->
                    "[\"" + UUID.randomUUID() + "\", \"" + UUID.randomUUID() + "\"]";

            // ── Map types ───────────────────────────────────────────────────────
            case "Map<String, String>",
                 "Map<String,String>" ->
                    "{\"" + RandomPicker.pick(DataPool.LOREM_WORDS)
                    + "\":\"" + RandomPicker.pick(DataPool.LOREM_WORDS) + "\"}";

            case "Map<String, Object>",
                 "Map<String,Object>",
                 "Map<String, ?>",
                 "Map" ->
                    "{\"key\":\"" + RandomPicker.pick(DataPool.LOREM_WORDS) + "\"}";

            // ── Char ─────────────────────────────────────────────────────────────
            case "char", "Character" -> "A";

            default -> null;
        };
    }

    // ── Numeric name refinement ───────────────────────────────────────────────

    /**
     * Produces a numeric value in [{@code min}, {@code max}) shaped by the parameter name.
     *
     * @param name lowercased parameter name
     * @param min  inclusive lower bound
     * @param max  exclusive upper bound
     * @return a numeric string; never {@code null}
     * @since 1.0
     */
    @NotNull
    private static String numericByName(String name, int min, int max) {
        if (SegmentMatcher.has(name, "age"))                            return String.valueOf(rnd().nextInt(62) + 18);
        if (SegmentMatcher.has(name, "year"))                           return String.valueOf(2020 + rnd().nextInt(6));
        if (SegmentMatcher.has(name, "month"))                          return String.valueOf(rnd().nextInt(12) + 1);
        if (SegmentMatcher.has(name, "day"))                            return String.valueOf(rnd().nextInt(28) + 1);
        if (SegmentMatcher.has(name, "page"))                           return "0";
        if (SegmentMatcher.has(name, "size", "limit", "max", "count")) return String.valueOf(rnd().nextInt(49) + 1);
        if (SegmentMatcher.has(name, "id", "pk"))                      return String.valueOf(rnd().nextInt(max - min) + min);
        return String.valueOf(rnd().nextInt(max - min) + min);
    }

    /**
     * Produces a decimal value shaped by the parameter name.
     *
     * @param name lowercased parameter name
     * @return a decimal string using {@link Locale#ROOT} as decimal separator; never {@code null}
     * @since 1.0
     */
    @NotNull
    private static String decimalByName(String name) {
        if (SegmentMatcher.has(name, "lat", "latitude"))
            return String.format(Locale.ROOT, "%.6f", rnd().nextDouble() * 180 - 90);
        if (SegmentMatcher.has(name, "lng", "lon", "longitude"))
            return String.format(Locale.ROOT, "%.6f", rnd().nextDouble() * 360 - 180);
        if (SegmentMatcher.has(name, "price", "cost", "amount", "fee", "salary"))
            return String.format(Locale.ROOT, "%.2f", rnd().nextDouble() * 999 + 1);
        if (SegmentMatcher.has(name, "rate", "percent", "ratio"))
            return String.format(Locale.ROOT, "%.4f", rnd().nextDouble());
        return String.format(Locale.ROOT, "%.2f", rnd().nextDouble() * 100);
    }

    private static ThreadLocalRandom rnd() {
        return ThreadLocalRandom.current();
    }
}
