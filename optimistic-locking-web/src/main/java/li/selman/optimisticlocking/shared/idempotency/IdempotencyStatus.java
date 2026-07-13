package li.selman.optimisticlocking.shared.idempotency;

import org.jmolecules.architecture.onion.simplified.DomainRing;

/**
 * Lifecycle of an {@link IdempotencyRecord}: {@code RESERVED} the moment a fresh key is claimed
 * (before the request has actually been handled), {@code COMPLETED} once its response has been
 * captured and is available for replay.
 */
@DomainRing
public enum IdempotencyStatus {
    RESERVED,
    COMPLETED
}
