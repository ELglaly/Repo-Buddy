package com.repoinspector.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoinspector.agent.dto.ExecutionRequest.ParameterValue;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Converts raw string parameter values from the IDE plugin into the Java objects
 * expected by the target repository method.
 *
 * <h3>Classloader safety</h3>
 * <p>The agent JAR declares all Spring dependencies as {@code compileOnly}.  In a
 * Spring Boot fat-JAR deployment the application classes (including
 * {@code org.springframework.data.domain.Pageable} and user-defined enums) are loaded
 * by Spring Boot's {@code LaunchedURLClassLoader}, while the agent itself runs under
 * the system classloader.  Referencing Spring classes via {@code Foo.class} or
 * {@code Foo.class.isAssignableFrom(type)} therefore produces either a
 * {@link NoClassDefFoundError} or a silent {@code false} depending on the runtime
 * environment — both cause the fall-through to {@code objectMapper.readValue} and the
 * resulting {@code InvalidDefinitionException}.
 *
 * <p>This class avoids all direct Spring class references at runtime.  Every Spring
 * type is identified by its fully-qualified name and constructed entirely via reflection
 * on the <em>application's</em> {@link ClassLoader} (obtained from the {@code type}
 * parameter received from {@link Method#getParameters()}).
 *
 * <h3>Resolution order inside {@link #convertSingle}</h3>
 * <ol>
 *   <li>{@code String} → pass-through.</li>
 *   <li>Numeric primitives / wrappers → parsed directly.</li>
 *   <li>{@code boolean} / {@code Boolean} → {@link Boolean#parseBoolean}.</li>
 *   <li>{@code Pageable} / {@code PageRequest} — detected by class-name walk, constructed
 *       reflectively as {@code PageRequest.of(page, size, sort)} using the app's
 *       classloader.  Supports JSON sort specs ({@code "name,asc"},
 *       {@code ["name,asc","id,desc"]}) and the {@code {"orders":[…]}} Spring Data format.</li>
 *   <li>Enum types → case-insensitive name lookup via {@link Class#getEnumConstants()};
 *       falls back to the first constant instead of crashing on unknown/blank input.</li>
 *   <li>Everything else → {@link ObjectMapper#readValue}.</li>
 * </ol>
 *
 * @since 1.0
 */
final class ParameterConverter {

    private static final String PAGEABLE_FQN     = "org.springframework.data.domain.Pageable";
    private static final String PAGE_REQUEST_FQN = "org.springframework.data.domain.PageRequest";
    private static final String SORT_FQN         = "org.springframework.data.domain.Sort";
    private static final String SORT_DIR_FQN     = "org.springframework.data.domain.Sort$Direction";
    private static final String SORT_ORDER_FQN   = "org.springframework.data.domain.Sort$Order";

    private final ObjectMapper objectMapper;

    ParameterConverter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Converts each {@link ParameterValue} to the Java type declared by {@code method}.
     *
     * @param method      the target repository method
     * @param paramValues raw values from the IDE plugin, one per parameter
     * @return argument array ready for {@link Method#invoke}
     * @throws Exception on parse failure or reflection error
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

    /**
     * Converts {@code value} to an instance of {@code type}.
     *
     * @param type  the target Java type (from the app's classloader)
     * @param value raw string value from the plugin UI; may be {@code null}
     * @return converted object, or {@code null} for blank/null input on nullable types
     * @throws Exception on parse or reflection failure
     */
    private Object convertSingle(Class<?> type, String value) throws Exception {

        // ── Primitives, wrappers, String ──────────────────────────────────────
        if (type == String.class)
            return nullableString(value);
        if (type == Long.class    || type == long.class)
            return value == null ? null : Long.parseLong(value.trim());
        if (type == Integer.class || type == int.class)
            return value == null ? null : Integer.parseInt(value.trim());
        if (type == Short.class   || type == short.class)
            return value == null ? null : Short.parseShort(value.trim());
        if (type == Byte.class    || type == byte.class)
            return value == null ? null : Byte.parseByte(value.trim());
        if (type == Double.class  || type == double.class)
            return value == null ? null : Double.parseDouble(value.trim());
        if (type == Float.class   || type == float.class)
            return value == null ? null : Float.parseFloat(value.trim());
        if (type == Boolean.class || type == boolean.class)
            return value == null ? null : Boolean.parseBoolean(value.trim());

        // ── Pageable / PageRequest ─────────────────────────────────────────────
        // Detected by class-name walk (not Pageable.class.isAssignableFrom) to
        // survive fat-JAR classloader isolation where the agent's system classloader
        // and Spring Boot's LaunchedURLClassLoader hold distinct Class objects.
        if (isPageableType(type))
            return buildPageRequest(type, value);

        // ── Enum types ─────────────────────────────────────────────────────────
        // type.isEnum() is a JVM-level flag on the app's own class — no classloader
        // ambiguity.  We resolve constants from the app's class via getEnumConstants()
        // so that any enum in any package works without prior registration.
        if (type.isEnum())
            return resolveEnum(type, value);

        // ── Class<T> projection parameter ─────────────────────────────────────
        // Spring Data projection methods accept a Class<T> to select the view:
        //   <T> T findById(Long id, Class<T> type)
        // The plugin sends the FQN as a plain string; load it from the app classloader.
        if (type == Class.class)
            return resolveProjectionClass(type.getClassLoader(), value);

        // ── Generic JSON fallback ─────────────────────────────────────────────
        if (value == null || value.isBlank()) return null;
        return objectMapper.readValue(value, type);
    }

    // =========================================================================
    // Pageable / PageRequest — fully reflective construction
    // =========================================================================

    /**
     * Returns {@code true} when {@code type} or any interface in its hierarchy has
     * the fully-qualified name {@link #PAGEABLE_FQN} or {@link #PAGE_REQUEST_FQN}.
     *
     * <p>Walking the hierarchy instead of using {@code Foo.class.isAssignableFrom}
     * avoids the classloader isolation problem described in the class Javadoc.
     *
     * @param type the class to test; never {@code null}
     * @return {@code true} if the type is assignable to Pageable
     */
    private static boolean isPageableType(Class<?> type) {
        return typeNameMatches(type, PAGEABLE_FQN) || typeNameMatches(type, PAGE_REQUEST_FQN);
    }

    /**
     * Walks the full type hierarchy (superclasses + interfaces, recursively) to find
     * a class whose {@link Class#getName()} equals {@code targetFqn}.
     *
     * @param type      starting class; {@code null} terminates the walk
     * @param targetFqn fully-qualified class name to find
     * @return {@code true} on first match
     */
    private static boolean typeNameMatches(Class<?> type, String targetFqn) {
        if (type == null) return false;
        if (type.getName().equals(targetFqn)) return true;
        for (Class<?> iface : type.getInterfaces()) {
            if (typeNameMatches(iface, targetFqn)) return true;
        }
        return typeNameMatches(type.getSuperclass(), targetFqn);
    }

    /**
     * Builds a {@code PageRequest} entirely via reflection on the application's classloader.
     *
     * <p>Accepted JSON shapes for {@code value}:
     * <pre>
     *   null / blank                    → PageRequest.of(0, 10)
     *   {"page":0, "size":20}           → PageRequest.of(0, 20)
     *   {"page":1, "size":5, "sort":"name,asc"}
     *   {"page":0, "size":10, "sort":["createdAt,desc","id,asc"]}
     *   {"page":0, "size":10, "sort":{"orders":[{"direction":"ASC","property":"id"}]}}
     * </pre>
     *
     * @param pageableType the runtime Pageable/PageRequest class from the app's classloader
     * @param value        raw JSON string from the plugin UI; may be {@code null} or blank
     * @return a {@code PageRequest} instance cast to {@code Object}; never {@code null}
     * @throws Exception on reflection or JSON parsing failure
     */
    private Object buildPageRequest(Class<?> pageableType, String value) throws Exception {
        ClassLoader cl = appClassLoader(pageableType);

        int page = 0;
        int size = 10;
        Object sort = buildUnsortedSort(cl);

        if (value != null && !value.isBlank()) {
            JsonNode root = objectMapper.readTree(value);
            page = root.path("page").asInt(0);
            size = Math.max(1, root.path("size").asInt(10));
            sort = parseSortNode(cl, root.path("sort"));
        }

        Class<?> pageRequestClass = cl.loadClass(PAGE_REQUEST_FQN);
        Class<?> sortClass        = cl.loadClass(SORT_FQN);
        Method   ofMethod         = pageRequestClass.getMethod("of", int.class, int.class, sortClass);
        return ofMethod.invoke(null, page, size, sort);
    }

    /**
     * Constructs a {@code Sort.unsorted()} instance using the app's classloader.
     *
     * @param cl the application classloader
     * @return a {@code Sort} instance; never {@code null}
     * @throws Exception on reflection failure
     */
    private static Object buildUnsortedSort(ClassLoader cl) throws Exception {
        Class<?> sortClass = cl.loadClass(SORT_FQN);
        return sortClass.getMethod("unsorted").invoke(null);
    }

    /**
     * Parses the optional {@code "sort"} JSON node into a reflective {@code Sort} instance.
     *
     * <p>Supported node shapes:
     * <ul>
     *   <li>Missing / null / {@code {"sorted":false,…}} → {@code Sort.unsorted()}</li>
     *   <li>{@code "name,asc"} (text) → {@code Sort.by(ASC, "name")}</li>
     *   <li>{@code ["name,asc","id,desc"]} (array of text) → multi-property sort</li>
     *   <li>{@code {"orders":[{"direction":"ASC","property":"id",…}]}} (Spring Data format)
     *       → parsed orders array</li>
     * </ul>
     *
     * @param cl       the application classloader
     * @param sortNode the JSON node for the {@code "sort"} field; may be missing
     * @return a {@code Sort} instance; never {@code null}
     * @throws Exception on reflection failure
     */
    private Object parseSortNode(ClassLoader cl, JsonNode sortNode) throws Exception {
        if (sortNode == null || sortNode.isMissingNode() || sortNode.isNull()) {
            return buildUnsortedSort(cl);
        }

        // Text: "property,direction"
        if (sortNode.isTextual()) {
            return buildSortFromSpecs(cl, List.of(sortNode.asText()));
        }

        // Array: ["property,direction", …]
        if (sortNode.isArray()) {
            List<String> specs = new ArrayList<>();
            for (JsonNode elem : sortNode) specs.add(elem.asText());
            return buildSortFromSpecs(cl, specs);
        }

        // Object: {"orders":[{"direction":"ASC","property":"id"}]}
        // Falls through to unsorted for the legacy {"sorted":false,"unsorted":true} shape.
        if (sortNode.isObject() && sortNode.has("orders")) {
            return buildSortFromOrdersArray(cl, sortNode.get("orders"));
        }

        return buildUnsortedSort(cl);
    }

    /**
     * Builds a {@code Sort} from a list of {@code "property[,direction]"} spec strings.
     * Defaults to {@code ASC} when the direction token is absent or unrecognised.
     *
     * @param cl    the application classloader
     * @param specs one or more sort spec strings
     * @return a {@code Sort} instance; {@code Sort.unsorted()} when {@code specs} is empty
     * @throws Exception on reflection failure
     */
    private Object buildSortFromSpecs(ClassLoader cl, List<String> specs) throws Exception {
        Class<?> sortClass  = cl.loadClass(SORT_FQN);
        Class<?> dirClass   = cl.loadClass(SORT_DIR_FQN);
        Class<?> orderClass = cl.loadClass(SORT_ORDER_FQN);

        Object asc = enumValueOf(dirClass, "ASC");

        List<Object> orders = new ArrayList<>();
        for (String spec : specs) {
            if (spec == null || spec.isBlank()) continue;
            String[] parts    = spec.split(",", 2);
            String   property = parts[0].trim();
            if (property.isEmpty()) continue;
            Object dir = asc;
            if (parts.length > 1) {
                try { dir = enumValueOf(dirClass, parts[1].trim().toUpperCase()); }
                catch (IllegalArgumentException ignored) { /* keep asc */ }
            }
            // Sort.Order(direction, property)
            orders.add(orderClass.getConstructor(dirClass, String.class).newInstance(dir, property));
        }

        if (orders.isEmpty()) return buildUnsortedSort(cl);

        // Sort.by(List<Order>)
        Method byList = sortClass.getMethod("by", List.class);
        return byList.invoke(null, orders);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Enum<?> enumValueOf(Class<?> enumClass, String name) {
        return Enum.valueOf((Class<Enum>) enumClass, name);
    }

    /**
     * Builds a {@code Sort} from the {@code "orders"} array in the Spring Data JSON format.
     *
     * <p>Each order element is expected to have at least a {@code "property"} field;
     * {@code "direction"} defaults to {@code ASC} when absent.
     *
     * @param cl         the application classloader
     * @param ordersNode the JSON array node; may be {@code null}
     * @return a {@code Sort} instance; {@code Sort.unsorted()} when the array is empty
     * @throws Exception on reflection failure
     */
    @SuppressWarnings("unchecked")
    private Object buildSortFromOrdersArray(ClassLoader cl, JsonNode ordersNode) throws Exception {
        if (ordersNode == null || !ordersNode.isArray() || ordersNode.isEmpty()) {
            return buildUnsortedSort(cl);
        }

        List<String> specs = new ArrayList<>();
        for (JsonNode orderNode : ordersNode) {
            String property  = orderNode.path("property").asText("id");
            String direction = orderNode.path("direction").asText("ASC");
            specs.add(property + "," + direction);
        }
        return buildSortFromSpecs(cl, specs);
    }

    // =========================================================================
    // Enum resolver
    // =========================================================================

    /**
     * Resolves a string value to one of {@code enumType}'s declared constants.
     *
     * <p>Uses {@code enumType.getEnumConstants()} — the app's own loaded class —
     * so any enum in any package works without prior registration.
     *
     * <p>Resolution strategy:
     * <ol>
     *   <li>Blank / placeholder ({@code null}, {@code ""}, {@code "{}"}, {@code "null"}) →
     *       return the <em>first</em> declared constant and log the available names.</li>
     *   <li>Case-insensitive name match → return the matching constant.</li>
     *   <li>No match → fall back to the first constant and log a warning that lists
     *       every available constant name.</li>
     * </ol>
     *
     * @param enumType the enum class from the app's classloader; never {@code null}
     * @param value    raw string value from the plugin UI; may be {@code null}
     * @return the resolved enum constant; never {@code null}
     * @throws IllegalArgumentException if {@code enumType} has no constants
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

        boolean isPlaceholder = value == null || value.isBlank()
                || "{}".equals(value) || "null".equals(value);
        if (isPlaceholder) {
            System.out.printf(
                    "[RepoBuddy] No value for enum %s — available: [%s]. Using first: '%s'.%n",
                    enumType.getSimpleName(), availableNames, ((Enum<?>) constants[0]).name());
            return constants[0];
        }

        String trimmed = value.trim();
        for (Object constant : constants) {
            if (((Enum) constant).name().equalsIgnoreCase(trimmed)) return constant;
        }

        System.out.printf(
                "[RepoBuddy] Unrecognised enum value '%s' for %s — available: [%s]. Using '%s'.%n",
                trimmed, enumType.getSimpleName(), availableNames, ((Enum<?>) constants[0]).name());
        return constants[0];
    }

    // =========================================================================
    // Class<T> projection
    // =========================================================================

    /**
     * Resolves a projection class from a fully-qualified class name string.
     *
     * <p>Spring Data projection methods accept a {@code Class<T>} to choose the result view:
     * {@code <T> T findById(Long id, Class<T> type)}.  The plugin sends the FQN as a plain
     * string (e.g. {@code "com.example.UserDto"}); this method loads it from the application
     * classloader so the correct type is passed to the repository method.
     *
     * @param appCl the application classloader (from the adjacent parameter's class)
     * @param value the FQN string supplied by the plugin UI; may be null/blank
     * @return the resolved {@link Class}, or {@code null} when value is blank
     * @throws ClassNotFoundException when the named class cannot be found
     */
    private static Class<?> resolveProjectionClass(ClassLoader appCl, String value)
            throws ClassNotFoundException {
        if (value == null || value.isBlank()) return null;
        String fqn = value.trim();
        ClassLoader cl = appCl != null ? appCl : Thread.currentThread().getContextClassLoader();
        return Class.forName(fqn, false, cl);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Returns the {@link ClassLoader} that loaded {@code type}, falling back to the
     * current thread's context classloader when {@code type} was loaded by the bootstrap
     * classloader (which returns {@code null} for {@link Class#getClassLoader()}).
     *
     * @param type any class; never {@code null}
     * @return a non-null classloader
     */
    private static ClassLoader appClassLoader(Class<?> type) {
        ClassLoader cl = type.getClassLoader();
        return cl != null ? cl : Thread.currentThread().getContextClassLoader();
    }

    private static String nullableString(String value) {
        return (value == null || "null".equals(value)) ? null : value;
    }
}
