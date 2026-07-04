package li.selman.optimisticlocking.shared;

import java.util.Arrays;
import java.util.stream.Stream;

/**
 * Matches a raw {@code If-Match}/{@code If-None-Match} header value against a current ETag,
 * per <a href="https://www.rfc-editor.org/rfc/rfc9110.html#section-13.1.1">RFC 9110 §13.1.1</a>
 * and <a href="https://www.rfc-editor.org/rfc/rfc9110.html#section-13.1.2">§13.1.2</a>: both
 * fields may carry a comma-separated list of entity-tags, or the single value {@code *} (which
 * matches any current representation, regardless of its actual tag).
 */
public final class ETagMatcher {

    private static final String WILDCARD = "*";

    private ETagMatcher() {}

    /**
     * If-Match uses <a href="https://www.rfc-editor.org/rfc/rfc9110.html#section-8.8.3.2">strong
     * comparison</a>: an entity-tag only matches an equal, non-weak opaque-tag.
     */
    public static boolean matchesIfMatch(String ifMatchHeader, String currentETag) {
        String trimmed = ifMatchHeader.trim();
        if (WILDCARD.equals(trimmed)) {
            return true;
        }
        return splitTags(trimmed).anyMatch(tag -> !isWeak(tag) && tag.equals(currentETag));
    }

    /**
     * If-None-Match uses <a href="https://www.rfc-editor.org/rfc/rfc9110.html#section-8.8.3.2">weak
     * comparison</a>: entity-tags match if their opaque-tags are equal, regardless of the {@code W/}
     * prefix on either side.
     */
    public static boolean matchesIfNoneMatch(String ifNoneMatchHeader, String currentETag) {
        String trimmed = ifNoneMatchHeader.trim();
        if (WILDCARD.equals(trimmed)) {
            return true;
        }
        return splitTags(trimmed).anyMatch(tag -> stripWeakPrefix(tag).equals(stripWeakPrefix(currentETag)));
    }

    private static Stream<String> splitTags(String headerValue) {
        return Arrays.stream(headerValue.split(",")).map(String::trim);
    }

    private static boolean isWeak(String tag) {
        return tag.startsWith("W/");
    }

    private static String stripWeakPrefix(String tag) {
        return isWeak(tag) ? tag.substring(2) : tag;
    }
}
