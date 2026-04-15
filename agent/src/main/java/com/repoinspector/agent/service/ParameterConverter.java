package com.repoinspector.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoinspector.agent.dto.ExecutionRequest.ParameterValue;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.domain.Sort.Order;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Converts string parameter values from the plugin UI into proper Java objects
 * matching the target repository method's parameter types.
 *
 * <p>Resolution order inside {@link #convertSingle}:
 * <ol>
 *   <li>Null / empty → {@code null} (except Pageable/Enum, which produce defaults)</li>
 *   <li>{@code String} → pass-through</li>
 *   <li>Numeric primitives / wrappers → parsed directly</li>
 *   <li>{@code Boolean} / {@code boolean} → {@link Boolean#parseBoolean}</li>
 *   <li><b>{@code Pageable} / {@code PageRequest}</b> → custom JSON → {@link PageRequest} builder
 *       (fixes Jackson {@code InvalidDefinitionException} on abstract interface)</li>
 *   <li><b>Enum types</b> → case-insensitive name match; falls back to first real constant
 *       when the value is blank, {@code "{}"}, or unrecognised (never throws)</li>
 *   <li>Everything else → {@link ObjectMapper#readValue} from JSON</li>
 * </ol>
 */
final class ParameterConverter {

    private final ObjectMapper objectMapper;

    ParameterConverter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Converts each {@link ParameterValue} in {@code paramValues} to the
     * corresponding Java type declared by {@code method}.
     *
     * @param method      the target repository method
     * @param paramValues raw string values from the plugin UI, one per parameter
     * @return array of converted arguments ready for {@link Method#invoke}
     */
    Object[] convert(Method method, List<ParameterValue> paramValues) throws Exception {
        Parameter[] params = method.getParameters();
        Object[] args = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            String raw = i < paramValues.size() ? paramValues.get(i).value() : null;
            args[i] = convertSingle(params[i].getType(), raw);
        }
        return args;
    }

    // =========================================================================
    // Core dispatch
    // =========================================================================

    private Object convertSingle(Class<?> type, String value) throws Exception {

        // ── Primitives, wrappers, String ──────────────────────────────────────
        if (type == String.class)                                return nullableString(value);
        if (type == Long.class    || type == long.class)         return value == null ? null : Long.parseLong(value.trim());
        if (type == Integer.class || type == int.class)          return value == null ? null : Integer.parseInt(value.trim());
        if (type == Short.class   || type == short.class)        return value == null ? null : Short.parseShort(value.trim());
        if (type == Byte.class    || type == byte.class)         return value == null ? null : Byte.parseByte(value.trim());
        if (type == Double.class  || type == double.class)       return value == null ? null : Double.parseDouble(value.trim());
        if (type == Float.class   || type == float.class)        return value == null ? null : Float.parseFloat(value.trim());
        if (type == Boolean.class || type == boolean.class)      return value == null ? null : Boolean.parseBoolean(value.trim());

        // ── BUG 1 FIX: Pageable / PageRequest ─────────────────────────────────
        // ObjectMapper cannot construct Pageable (abstract interface with no creators).
        // We parse the JSON fields manually and delegate to PageRequest.of().
        if (Pageable.class.isAssignableFrom(type)) return parsePageable(value);

        // ── BUG 2 FIX: Enum types ─────────────────────────────────────────────
        // Always resolve against the real enum constants loaded by the app's
        // ClassLoader.  Falls back to the first constant instead of crashing
        // when the plugin sends a placeholder like "{}" or an unrecognised name.
        if (type.isEnum()) return resolveEnum(type, value);

        // ── Generic JSON fallback ─────────────────────────────────────────────
        if (value == null || value.isBlank()) return null;
        return objectMapper.readValue(value, type);
    }

    // =========================================================================
    // BUG 1: Pageable parser
    // =========================================================================

    /**
     * Constructs a {@link PageRequest} from a JSON string.
     *
     * <p>Accepted JSON shapes:
     * <pre>
     *   {"page": 0, "size": 20}
     *   {"page": 1, "size": 10, "sort": "name,asc"}
     *   {"page": 0, "size": 5,  "sort": ["createdAt,desc", "id,asc"]}
     *   {"page": 0, "size": 10, "sort": {"sorted": false}}   ← unsorted sentinel
     * </pre>
     *
     * <p>Missing or blank input → {@code PageRequest.of(0, 10)}.
     */
    private PageRequest parsePageable(String value) throws Exception {
        if (value == null || value.isBlank()) {
            return PageRequest.of(0, 10);
        }

        JsonNode root = objectMapper.readTree(value);

        int page = root.path("page").asInt(0);
        int size = Math.max(1, root.path("size").asInt(10));
        Sort sort = parseSort(root.path("sort"));

        return PageRequest.of(page, size, sort);
    }

    /**
     * Parses the optional {@code "sort"} node from the Pageable JSON.
     *
     * <p>Supported node types:
     * <ul>
     *   <li>{@code "name,asc"} (text)  → {@code Sort.by(ASC, "name")}</li>
     *   <li>{@code ["name,asc","id,desc"]} (array) → multiple orders</li>
     *   <li>{@code {"sorted":false}} (object sentinel) → {@link Sort#unsorted()}</li>
     *   <li>missing / null → {@link Sort#unsorted()}</li>
     * </ul>
     */
    private static Sort parseSort(JsonNode sortNode) {
        if (sortNode == null || sortNode.isMissingNode() || sortNode.isNull()) {
            return Sort.unsorted();
        }

        // Object sentinel from the UI: {"sorted":false,"unsorted":true} — always unsorted.
        // We cannot reconstruct full sort criteria from this shape alone.
        if (sortNode.isObject()) {
            return Sort.unsorted();
        }

        // Single sort spec string: "property,direction"
        if (sortNode.isTextual()) {
            return parseSortSpec(sortNode.asText());
        }

        // Array of sort spec strings
        if (sortNode.isArray()) {
            List<Order> orders = new ArrayList<>();
            for (JsonNode element : sortNode) {
                parseSortSpec(element.asText()).forEach(orders::add);
            }
            return orders.isEmpty() ? Sort.unsorted() : Sort.by(orders);
        }

        return Sort.unsorted();
    }

    /**
     * Parses a single sort specification string of the form {@code "property"} or
     * {@code "property,direction"} (e.g. {@code "name,asc"}, {@code "createdAt,DESC"}).
     * Defaults to {@link Direction#ASC} when the direction token is absent or invalid.
     */
    private static Sort parseSortSpec(String spec) {
        if (spec == null || spec.isBlank()) return Sort.unsorted();

        String[] parts = spec.split(",", 2);
        String property = parts[0].trim();
        if (property.isEmpty()) return Sort.unsorted();

        Direction direction = Direction.ASC;
        if (parts.length > 1) {
            try {
                direction = Direction.fromString(parts[1].trim());
            } catch (IllegalArgumentException ignored) {
                // unrecognised direction token → keep ASC default
            }
        }

        return Sort.by(direction, property);
    }

    // =========================================================================
    // BUG 2: Enum resolver
    // =========================================================================

    /**
     * Resolves an enum constant from the app's own loaded {@code enumType} class.
     *
     * <p>Resolution strategy:
     * <ol>
     *   <li>Blank / placeholder value ({@code null}, {@code ""}, {@code "{}"}) →
     *       return the <em>first</em> declared constant and log the available names.</li>
     *   <li>Case-insensitive name match → return the matching constant.</li>
     *   <li>No match → fall back to the first constant and log a warning that lists
     *       every available constant so the developer can correct the input.</li>
     * </ol>
     *
     * <p>This method never throws so that placeholder values generated by the plugin
     * UI (e.g. {@code "{}"}) do not crash execution.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object resolveEnum(Class<?> enumType, String value) {
        Object[] constants = enumType.getEnumConstants();
        if (constants == null || constants.length == 0) {
            throw new IllegalArgumentException(
                    "[RepoBuddy] Enum " + enumType.getName() + " has no constants.");
        }

        String availableNames = Arrays.stream(constants)
                .map(c -> ((Enum<?>) c).name())
                .collect(Collectors.joining(", "));

        // Blank or UI placeholder → use first constant
        boolean isPlaceholder = value == null || value.isBlank()
                || value.equals("{}") || value.equals("null");
        if (isPlaceholder) {
            System.out.printf(
                    "[RepoBuddy] No value supplied for enum %s. "
                    + "Available: [%s]. Using first: '%s'.%n",
                    enumType.getSimpleName(), availableNames,
                    ((Enum<?>) constants[0]).name());
            return constants[0];
        }

        // Case-insensitive name match
        String trimmed = value.trim();
        for (Object constant : constants) {
            if (((Enum) constant).name().equalsIgnoreCase(trimmed)) {
                return constant;
            }
        }

        // No match → fall back to first constant with a helpful log line
        System.out.printf(
                "[RepoBuddy] Unrecognised enum value '%s' for %s. "
                + "Available: [%s]. Falling back to '%s'.%n",
                trimmed, enumType.getSimpleName(), availableNames,
                ((Enum<?>) constants[0]).name());
        return constants[0];
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static String nullableString(String value) {
        return (value == null || value.equals("null")) ? null : value;
    }
}
