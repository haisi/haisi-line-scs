package li.selman.optimisticlocking.shared.idempotency;

import org.jmolecules.architecture.onion.simplified.DomainRing;

/** Outcome of {@link IdempotencyService#reserve}, telling {@code IdempotencyFilter} what to do next. */
@DomainRing
public sealed interface IdempotencyReservationResult {

    /** No prior record for this key: the caller should proceed and eventually {@code complete}/{@code abandon}. */
    record Reserved() implements IdempotencyReservationResult {}

    /** A completed record for this exact request (matching fingerprint): replay its response, don't re-execute. */
    record Replay(IdempotencyRecord record) implements IdempotencyReservationResult {}

    /** The key is already recorded, but for a different request: 422, per the Idempotency-Key draft. */
    record FingerprintMismatch() implements IdempotencyReservationResult {}

    /** Another request with this exact key is still being handled: 409, try again shortly. */
    record InProgress() implements IdempotencyReservationResult {}
}
