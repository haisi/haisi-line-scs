package li.selman.optimisticlocking.shared;

import org.jspecify.annotations.Nullable;

/**
 * Value object for the {@code If-Match} request header (RFC 9110 §13.1.1), resolved directly onto
 * controller method parameters by {@link li.selman.optimisticlocking.shared.web.IfMatchArgumentResolver}.
 */
public final class IfMatch {

    private static final IfMatch ABSENT = new IfMatch(null);

    private final @Nullable String headerValue;

    private IfMatch(@Nullable String headerValue) {
        this.headerValue = headerValue;
    }

    public static IfMatch of(@Nullable String headerValue) {
        return headerValue == null ? ABSENT : new IfMatch(headerValue);
    }

    public boolean isAbsent() {
        return headerValue == null;
    }

    /** Strong comparison per RFC 9110 §13.1.1; always {@code false} when the header is absent. */
    public boolean matches(String currentETag) {
        return headerValue != null && ETagMatcher.matchesIfMatch(headerValue, currentETag);
    }
}
