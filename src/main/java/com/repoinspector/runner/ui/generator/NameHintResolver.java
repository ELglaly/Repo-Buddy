package com.repoinspector.runner.ui.generator;

import com.repoinspector.runner.model.ParameterDef;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * {@link GenerationStrategy} that dispatches on the parameter <em>name</em>.
 *
 * <p>Matches are evaluated top-to-bottom; the first matching rule wins.
 * All matching uses {@link SegmentMatcher#has} to check word-boundary positions, avoiding
 * false positives from plain substring search (e.g. {@code "lat"} must not match
 * {@code "platform"}, {@code "id"} must not match {@code "validity"}).
 *
 * <p>The {@code "id"}/{@code "pk"} rule is intentionally placed last: it is the most
 * general identifier hint and must not shadow more-specific rules such as {@code "price"}
 * or {@code "code"}.
 *
 * <p>This strategy handles all types, including {@code String}, unknown object types, and
 * any type not recognised by {@link TypeNameResolver}.
 *
 * @since 1.0
 */
public final class NameHintResolver implements GenerationStrategy {

    @Override
    public boolean supports(@NotNull ParameterDef param) {
        return resolve(param.name().toLowerCase(Locale.ROOT)) != null;
    }

    @Override
    @NotNull
    public String generate(@NotNull ParameterDef param) {
        String result = resolve(param.name().toLowerCase(Locale.ROOT));
        return result != null ? result : "";
    }

    /**
     * Core name-hint dispatch.
     *
     * @param name lowercased parameter name
     * @return a sample value string, or {@code null} when no hint matches
     * @since 1.0
     */
    @Nullable
    static String resolve(String name) {
        if (SegmentMatcher.has(name, "email", "mail"))
            return ValueBuilders.randomEmail();
        if (SegmentMatcher.has(name, "firstname", "first_name", "givenname"))
            return RandomPicker.pick(DataPool.FIRST_NAMES);
        if (SegmentMatcher.has(name, "lastname", "last_name", "surname", "familyname"))
            return RandomPicker.pick(DataPool.LAST_NAMES);
        if (SegmentMatcher.has(name, "fullname", "displayname", "name"))
            return ValueBuilders.randomFullName();
        if (SegmentMatcher.has(name, "username", "user_name", "login", "handle", "alias"))
            return ValueBuilders.randomUsername();
        if (SegmentMatcher.has(name, "password", "passwd", "pwd", "pass"))
            return "Passw0rd!";
        if (SegmentMatcher.has(name, "phone", "mobile", "cell", "tel", "fax"))
            return ValueBuilders.randomPhone();
        if (SegmentMatcher.has(name, "street", "address", "addr"))
            return ValueBuilders.randomStreet();
        if (SegmentMatcher.has(name, "city", "town", "locality"))
            return RandomPicker.pick(DataPool.CITIES);
        if (SegmentMatcher.has(name, "country", "nation"))
            return RandomPicker.pick(DataPool.COUNTRIES);
        if (SegmentMatcher.has(name, "zip", "postal", "postcode"))
            return ValueBuilders.randomZip();

        // Geo-coordinates: use explicit segment + full-word checks to prevent
        // "platform", "flatrate" from matching "lat", and similar false positives.
        if (SegmentMatcher.has(name, "lat", "latitude"))
            return String.format(Locale.ROOT, "%.6f", rnd().nextDouble() * 180 - 90);
        if (SegmentMatcher.has(name, "lng", "lon", "longitude"))
            return String.format(Locale.ROOT, "%.6f", rnd().nextDouble() * 360 - 180);

        if (SegmentMatcher.has(name, "url", "uri", "link", "href", "website"))
            return "https://example.com/" + RandomPicker.pick(DataPool.LOREM_WORDS);
        if (SegmentMatcher.has(name, "image", "photo", "avatar", "thumbnail", "picture", "img"))
            return "https://example.com/images/" + ValueBuilders.randomCode(8) + ".jpg";
        if (SegmentMatcher.has(name, "description", "desc", "summary", "bio", "about", "detail"))
            return ValueBuilders.randomSentence();
        if (SegmentMatcher.has(name, "comment", "note", "remark", "message", "content", "body", "text"))
            return ValueBuilders.randomSentence();
        if (SegmentMatcher.has(name, "title", "subject", "heading", "caption"))
            return "Sample " + ValueBuilders.capitalise(RandomPicker.pick(DataPool.LOREM_WORDS));
        if (SegmentMatcher.has(name, "slug", "permalink"))
            return RandomPicker.pick(DataPool.LOREM_WORDS) + "-" + RandomPicker.pick(DataPool.LOREM_WORDS);
        if (SegmentMatcher.has(name, "tag", "label", "category", "group", "type", "kind"))
            return ValueBuilders.capitalise(RandomPicker.pick(DataPool.LOREM_WORDS));
        if (SegmentMatcher.has(name, "color", "colour"))
            return ValueBuilders.randomHexColor();
        if (SegmentMatcher.has(name, "status", "state", "flag"))
            return RandomPicker.pick(DataPool.STATUSES);
        if (SegmentMatcher.has(name, "method", "httpmethod"))
            return RandomPicker.pick(DataPool.HTTP_METHODS);

        // "key" appears here (not in the id/pk group below) because a "key" parameter
        // typically holds an opaque token/code, not a sequential integer ID.
        if (SegmentMatcher.has(name, "code", "token", "key", "secret", "hash", "digest", "ref", "reference"))
            return ValueBuilders.randomCode(12);

        if (SegmentMatcher.has(name, "age"))
            return String.valueOf(rnd().nextInt(62) + 18);
        if (SegmentMatcher.has(name, "price", "cost", "amount", "total", "fee", "rate", "salary"))
            return String.format(Locale.ROOT, "%.2f", rnd().nextDouble() * 999 + 1);
        if (SegmentMatcher.has(name, "count", "quantity", "qty", "num", "number", "limit", "max", "size"))
            return String.valueOf(rnd().nextInt(99) + 1);
        if (SegmentMatcher.has(name, "page"))
            return "0";

        // Most-general rule — must remain last to avoid shadowing more-specific hints above.
        if (SegmentMatcher.has(name, "id", "pk"))
            return String.valueOf(rnd().nextInt(DataPool.MAX_ID - 1) + 1);

        return null;
    }

    private static ThreadLocalRandom rnd() {
        return ThreadLocalRandom.current();
    }
}
