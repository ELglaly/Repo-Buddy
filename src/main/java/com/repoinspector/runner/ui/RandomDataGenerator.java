package com.repoinspector.runner.ui;

import com.repoinspector.runner.model.ParameterDef;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Generates realistic random sample values for repository method parameters.
 *
 * <p>Resolution order for each parameter:
 * <ol>
 *   <li><b>Type-name rules</b> — known types such as {@code Pageable}, {@code UUID},
 *       {@code LocalDate}, {@code BigDecimal}, {@code Sort}, etc.</li>
 *   <li><b>Name-hint rules</b> — the parameter name is scanned for substrings like
 *       {@code email}, {@code phone}, {@code city}, etc. to produce domain-aware values.</li>
 *   <li><b>Field-type fallback</b> — the broad {@link ParameterDef.FieldType} category
 *       (NUMBER, DECIMAL, BOOLEAN, TEXT, JSON) supplies a sensible default.</li>
 * </ol>
 *
 * <p>This class is stateless; every public method is {@code static} (SRP utility).
 */
final class RandomDataGenerator {

    // ── Sample data pools ─────────────────────────────────────────────────────

    private static final String[] FIRST_NAMES = {
            "Alice", "Bob", "Carol", "David", "Emma", "Frank",
            "Grace", "Henry", "Isabelle", "Jack", "Kate", "Liam"
    };
    private static final String[] LAST_NAMES = {
            "Smith", "Johnson", "Williams", "Brown", "Jones",
            "Garcia", "Miller", "Davis", "Wilson", "Taylor"
    };
    private static final String[] EMAIL_DOMAINS = {
            "example.com", "mail.dev", "test.io", "demo.org", "sample.net"
    };
    private static final String[] CITIES = {
            "New York", "London", "Paris", "Berlin", "Tokyo",
            "Sydney", "Toronto", "Amsterdam", "Singapore", "Dubai"
    };
    private static final String[] COUNTRIES = {
            "United States", "United Kingdom", "Germany", "France",
            "Japan", "Australia", "Canada", "Netherlands"
    };
    private static final String[] LOREM_WORDS = {
            "lorem", "ipsum", "dolor", "sit", "amet", "consectetur",
            "adipiscing", "elit", "sed", "do", "eiusmod", "tempor",
            "incididunt", "ut", "labore", "dolore", "magna", "aliqua"
    };
    private static final String[] STATUSES = {
            "ACTIVE", "INACTIVE", "PENDING", "ENABLED", "DISABLED"
    };
    private static final String[] HTTP_METHODS = { "GET", "POST", "PUT", "DELETE" };
    private static final String[] HEX_CHARS    = {
            "0","1","2","3","4","5","6","7","8","9","a","b","c","d","e","f"
    };

    private RandomDataGenerator() {}

    // =========================================================================
    // Public entry point
    // =========================================================================

    /**
     * Returns a random sample value for the given parameter as a {@link String}
     * suitable for the corresponding input widget (plain text or JSON).
     *
     * @param param parameter descriptor from PSI
     * @return a non-null sample value string
     */
    static String generate(ParameterDef param) {
        String type = param.typeName().trim();
        String name = param.name().toLowerCase();

        // ── 1. Known type rules ───────────────────────────────────────────────
        String byType = byTypeName(type, name);
        if (byType != null) return byType;

        // ── 2. Name-hint rules (String / TEXT / unknown types) ────────────────
        String byName = byNameHint(name);
        if (byName != null) return byName;

        // ── 3. FieldType fallback ─────────────────────────────────────────────
        return byFieldType(param.fieldType());
    }

    // =========================================================================
    // Type-name dispatch
    // =========================================================================

    private static String byTypeName(String type, String name) {
        // Strip generic wrapper Optional<X> → recurse on X
        if (type.startsWith("Optional<") && type.endsWith(">")) {
            String inner = type.substring("Optional<".length(), type.length() - 1);
            return generate(new ParameterDef(name, inner));
        }

        return switch (type) {

            // ── Pagination ────────────────────────────────────────────────────
            case "Pageable", "PageRequest",
                 "org.springframework.data.domain.Pageable",
                 "org.springframework.data.domain.PageRequest" ->
                    "{\"page\": 0, \"size\": 10}";

            case "Sort", "org.springframework.data.domain.Sort" ->
                    "{\"sorted\": false, \"unsorted\": true}";

            // ── UUID ──────────────────────────────────────────────────────────
            case "UUID", "java.util.UUID" ->
                    UUID.randomUUID().toString();

            // ── BigDecimal ────────────────────────────────────────────────────
            case "BigDecimal", "java.math.BigDecimal" ->
                    String.format("%.2f", rnd().nextDouble() * 999 + 1);

            // ── Date / Time ───────────────────────────────────────────────────
            case "LocalDate", "java.time.LocalDate" ->
                    LocalDate.now().minusDays(rnd().nextInt(365)).toString();

            case "LocalDateTime", "java.time.LocalDateTime" ->
                    LocalDateTime.now().minusDays(rnd().nextInt(365))
                            .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);

            case "ZonedDateTime", "java.time.ZonedDateTime",
                 "OffsetDateTime", "java.time.OffsetDateTime" ->
                    LocalDateTime.now().minusDays(rnd().nextInt(365))
                            .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "Z";

            case "Date", "java.util.Date", "java.sql.Date" ->
                    LocalDate.now().minusDays(rnd().nextInt(365)).toString();

            case "Timestamp", "java.sql.Timestamp" ->
                    String.valueOf(System.currentTimeMillis()
                            - (long) rnd().nextInt(86_400_000) * 30);

            // ── Numeric primitives / wrappers ─────────────────────────────────
            case "int", "Integer", "java.lang.Integer" -> numericByName(name, 1, 10_000);
            case "long", "Long", "java.lang.Long"      -> numericByName(name, 1, 100_000);
            case "short", "Short"                       -> String.valueOf(rnd().nextInt(1000) + 1);
            case "byte",  "Byte"                        -> String.valueOf(rnd().nextInt(127));
            case "double", "Double"                     -> decimalByName(name);
            case "float",  "Float"                      -> decimalByName(name);

            // ── Boolean ───────────────────────────────────────────────────────
            case "boolean", "Boolean", "java.lang.Boolean" ->
                    rnd().nextBoolean() ? "true" : "false";

            // ── String ────────────────────────────────────────────────────────
            case "String", "java.lang.String" ->
                    stringByName(name);

            // ── Common list / set types ───────────────────────────────────────
            case "List<String>", "Set<String>", "Collection<String>" ->
                    "[\"" + pickWord() + "\", \"" + pickWord() + "\"]";

            case "List<Long>", "List<Integer>",
                 "Set<Long>",  "Set<Integer>" ->
                    "[" + (rnd().nextInt(900) + 1) + ", " + (rnd().nextInt(900) + 1) + "]";

            // ── Char / Character ──────────────────────────────────────────────
            case "char", "Character" -> "A";

            default -> null;   // fall through to name-hint and field-type rules
        };
    }

    // =========================================================================
    // Name-hint dispatch
    // =========================================================================

    private static String byNameHint(String name) {
        if (contains(name, "email", "mail"))                           return randomEmail();
        if (contains(name, "firstname", "first_name", "givenname"))    return pick(FIRST_NAMES);
        if (contains(name, "lastname", "last_name", "surname",
                          "familyname"))                               return pick(LAST_NAMES);
        if (contains(name, "fullname", "displayname", "name"))         return randomFullName();
        if (contains(name, "username", "user_name", "login",
                          "handle", "alias"))                          return randomUsername();
        if (contains(name, "password", "passwd", "pwd", "pass"))       return "Passw0rd!";
        if (contains(name, "phone", "mobile", "cell", "tel",
                          "fax"))                                       return randomPhone();
        if (contains(name, "street", "address", "addr"))               return randomStreet();
        if (contains(name, "city", "town", "locality"))                return pick(CITIES);
        if (contains(name, "country", "nation"))                       return pick(COUNTRIES);
        if (contains(name, "zip", "postal", "postcode"))               return randomZip();
        if (contains(name, "latitude", "lat"))                         return String.format("%.6f",  rnd().nextDouble() * 180 - 90);
        if (contains(name, "longitude", "lng", "lon"))                 return String.format("%.6f", rnd().nextDouble() * 360 - 180);
        if (contains(name, "url", "uri", "link", "href", "website"))   return "https://example.com/" + pickWord();
        if (contains(name, "image", "photo", "avatar", "thumbnail",
                          "picture", "img"))                           return "https://example.com/images/" + randomCode(8) + ".jpg";
        if (contains(name, "description", "desc", "summary",
                          "bio", "about", "detail"))                   return randomSentence();
        if (contains(name, "comment", "note", "remark", "message",
                          "content", "body", "text"))                  return randomSentence();
        if (contains(name, "title", "subject", "heading", "caption"))  return "Sample " + capitalise(pickWord());
        if (contains(name, "slug", "permalink"))                       return pickWord() + "-" + pickWord();
        if (contains(name, "tag", "label", "category", "group",
                          "type", "kind"))                             return capitalise(pickWord());
        if (contains(name, "color", "colour"))                         return randomHexColor();
        if (contains(name, "status", "state", "flag"))                 return pick(STATUSES);
        if (contains(name, "method", "httpmethod"))                    return pick(HTTP_METHODS);
        if (contains(name, "code", "token", "key", "secret",
                          "hash", "digest", "ref", "reference"))       return randomCode(12);
        if (contains(name, "age"))                                     return String.valueOf(rnd().nextInt(62) + 18);
        if (contains(name, "price", "cost", "amount",
                          "total", "fee", "rate", "salary"))           return String.format("%.2f", rnd().nextDouble() * 999 + 1);
        if (contains(name, "count", "quantity", "qty", "num",
                          "number", "limit", "max", "size"))           return String.valueOf(rnd().nextInt(99) + 1);
        if (contains(name, "page"))                                    return "0";
        if (contains(name, "id", "pk", "key"))                        return String.valueOf(rnd().nextInt(9999) + 1);

        return null;
    }

    // =========================================================================
    // FieldType fallback
    // =========================================================================

    private static String byFieldType(ParameterDef.FieldType fieldType) {
        return switch (fieldType) {
            case BOOLEAN -> rnd().nextBoolean() ? "true" : "false";
            case NUMBER  -> String.valueOf(rnd().nextInt(9999) + 1);
            case DECIMAL -> String.format("%.2f", rnd().nextDouble() * 100);
            case TEXT    -> randomFullName();
            case JSON    -> "{}";
        };
    }

    // =========================================================================
    // Numeric / decimal helpers
    // =========================================================================

    private static String numericByName(String name, int min, int max) {
        if (contains(name, "age"))                              return String.valueOf(rnd().nextInt(62) + 18);
        if (contains(name, "year"))                             return String.valueOf(2020 + rnd().nextInt(6));
        if (contains(name, "month"))                            return String.valueOf(rnd().nextInt(12) + 1);
        if (contains(name, "day"))                              return String.valueOf(rnd().nextInt(28) + 1);
        if (contains(name, "page"))                             return "0";
        if (contains(name, "size", "limit", "max", "count"))   return String.valueOf(rnd().nextInt(49) + 1);
        if (contains(name, "id", "pk"))                        return String.valueOf(rnd().nextInt(max - min) + min);
        return String.valueOf(rnd().nextInt(max - min) + min);
    }

    private static String decimalByName(String name) {
        if (contains(name, "lat"))                                        return String.format("%.6f", rnd().nextDouble() * 180 - 90);
        if (contains(name, "lng", "lon"))                                 return String.format("%.6f", rnd().nextDouble() * 360 - 180);
        if (contains(name, "price", "cost", "amount", "fee", "salary"))  return String.format("%.2f", rnd().nextDouble() * 999 + 1);
        if (contains(name, "rate", "percent", "ratio"))                   return String.format("%.4f", rnd().nextDouble());
        return String.format("%.2f", rnd().nextDouble() * 100);
    }

    private static String stringByName(String name) {
        String byHint = byNameHint(name);
        return byHint != null ? byHint : randomFullName();
    }

    // =========================================================================
    // Value builders
    // =========================================================================

    private static String randomEmail() {
        String local = FIRST_NAMES[rnd().nextInt(FIRST_NAMES.length)].toLowerCase()
                + "." + LAST_NAMES[rnd().nextInt(LAST_NAMES.length)].toLowerCase()
                + rnd().nextInt(99);
        return local + "@" + pick(EMAIL_DOMAINS);
    }

    private static String randomFullName() {
        return pick(FIRST_NAMES) + " " + pick(LAST_NAMES);
    }

    private static String randomUsername() {
        return FIRST_NAMES[rnd().nextInt(FIRST_NAMES.length)].toLowerCase()
                + "_" + rnd().nextInt(999);
    }

    private static String randomPhone() {
        return "+1-" + (200 + rnd().nextInt(800))
                + "-" + String.format("%03d", rnd().nextInt(1000))
                + "-" + String.format("%04d", rnd().nextInt(10000));
    }

    private static String randomStreet() {
        return (rnd().nextInt(9999) + 1) + " " + capitalise(pickWord()) + " St";
    }

    private static String randomZip() {
        return String.format("%05d", rnd().nextInt(100000));
    }

    private static String randomSentence() {
        StringBuilder sb = new StringBuilder();
        int words = rnd().nextInt(8) + 5;
        for (int i = 0; i < words; i++) {
            if (i > 0) sb.append(' ');
            String w = LOREM_WORDS[rnd().nextInt(LOREM_WORDS.length)];
            sb.append(i == 0 ? capitalise(w) : w);
        }
        sb.append('.');
        return sb.toString();
    }

    private static String randomHexColor() {
        StringBuilder sb = new StringBuilder("#");
        for (int i = 0; i < 6; i++) {
            sb.append(HEX_CHARS[rnd().nextInt(HEX_CHARS.length)]);
        }
        return sb.toString();
    }

    static String randomCode(int length) {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(rnd().nextInt(chars.length())));
        }
        return sb.toString();
    }

    private static String randomCode() {
        return randomCode(12);
    }

    private static String pickWord() {
        return LOREM_WORDS[rnd().nextInt(LOREM_WORDS.length)];
    }

    // =========================================================================
    // Tiny utilities
    // =========================================================================

    private static ThreadLocalRandom rnd() {
        return ThreadLocalRandom.current();
    }

    private static <T> T pick(T[] array) {
        return array[rnd().nextInt(array.length)];
    }

    /** Returns {@code true} if {@code haystack} contains any of the given needles. */
    private static boolean contains(String haystack, String... needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) return true;
        }
        return false;
    }

    private static String capitalise(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
