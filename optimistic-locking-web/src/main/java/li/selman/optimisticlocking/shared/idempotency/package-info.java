/**
 * Idempotency-Key storage and reservation logic behind {@code shared.web.IdempotencyFilter}. Mixes
 * rings (like {@code line} does for {@code Line}/{@code LineService}) so no package-level ring
 * annotation here: {@link li.selman.optimisticlocking.shared.idempotency.IdempotencyService} is
 * {@code @ApplicationRing}, everything else in this package is {@code @DomainRing} -- see each
 * class's own annotation.
 */
@NullMarked
package li.selman.optimisticlocking.shared.idempotency;

import org.jspecify.annotations.NullMarked;
