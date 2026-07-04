package li.selman.optimisticlocking.shared;

import org.jspecify.annotations.Nullable;

/**
 * Value object for the {@code If-None-Match} request header (RFC 9110 §13.1.2), resolved directly
 * onto controller method parameters by
 * {@link li.selman.optimisticlocking.shared.web.IfNoneMatchArgumentResolver}.
 */
public final class IfNoneMatch {

    private static final IfNoneMatch ABSENT = new IfNoneMatch(null);

    private final @Nullable String headerValue;

    private IfNoneMatch(@Nullable String headerValue) {
        this.headerValue = headerValue;
    }

    public static IfNoneMatch of(@Nullable String headerValue) {
        return headerValue == null ? ABSENT : new IfNoneMatch(headerValue);
    }

    public boolean isAbsent() {
        return headerValue == null;
    }

    /** Weak comparison per RFC 9110 §13.1.2; always {@code false} when the header is absent. */
    public boolean matches(String currentETag) {
        return headerValue != null && ETagMatcher.matchesIfNoneMatch(headerValue, currentETag);
    }
}
