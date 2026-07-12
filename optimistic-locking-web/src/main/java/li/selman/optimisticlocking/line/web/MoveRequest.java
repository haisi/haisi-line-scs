package li.selman.optimisticlocking.line.web;

import jakarta.validation.constraints.Positive;

/** {@code by} is always a magnitude now -- the URL (which point, {@code move-left}/{@code move-right}) fixes the sign. */
public record MoveRequest(@Positive int by) {}
