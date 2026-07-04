package li.selman.optimisticlocking.shared.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

import java.util.UUID;

/**
 * Records that a move was already applied under a given Idempotency-Key, so a retry can be
 * recognised and skipped instead of re-applying the change. The fingerprint lets us detect a
 * key being reused for a genuinely different request.
 */
@Entity
public class IdempotencyKey {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String fingerprint;

    protected IdempotencyKey() {
        // Make JPA happy
    }

    public IdempotencyKey(UUID id, String fingerprint) {
        this.id = id;
        this.fingerprint = fingerprint;
    }

    public String getFingerprint() {
        return fingerprint;
    }
}
