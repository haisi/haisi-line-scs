package li.selman.optimisticlocking.shared.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Duration;
import java.time.Instant;
import org.jmolecules.architecture.onion.simplified.DomainRing;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Persistable;

/**
 * Records that a request carrying a given {@code Idempotency-Key} has been seen, so a retry can be
 * recognised and replayed instead of re-executed. Written by {@code IdempotencyFilter} -- not
 * specific to any one endpoint or aggregate, unlike the {@code Line}-scoped mechanism this replaces
 * (see {@code CLAUDE.md}).
 *
 * <p>{@link #id} is the raw {@code Idempotency-Key} header value verbatim (an opaque client-chosen
 * string per the IETF Idempotency-Key draft, not required to be a UUID). {@link #fingerprint} lets
 * a reuse of the same key for a genuinely different request be told apart from a real retry.
 *
 * <p>Implements {@link Persistable} purely so {@code save()}/{@code saveAndFlush()} always attempt
 * an INSERT for a freshly-constructed record, never a JPA {@code merge} -- with an application-
 * assigned (not {@code @GeneratedValue}) id, Spring Data JPA's default "is this new?" heuristic
 * would otherwise treat any instance carrying a non-null id as pre-existing and silently upsert it,
 * which would defeat the entire point of {@link IdempotencyService#reserve}: two concurrent
 * reservations for the same fresh key must genuinely race on a real INSERT's unique-key
 * constraint, not both quietly "succeed" by each overwriting the other.
 */
@Entity
@Table(name = "idempotency_record")
@DomainRing
public class IdempotencyRecord implements Persistable<String> {

    @Id
    private String id;

    @Transient
    private boolean isNew = true;

    @Column(nullable = false)
    private String fingerprint;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IdempotencyStatus status;

    @Column(name = "response_status")
    private @Nullable Integer responseStatus;

    @Lob
    @Column(name = "response_headers")
    private @Nullable String responseHeaders;

    @Lob
    @Column(name = "response_body")
    private byte @Nullable [] responseBody;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private @Nullable Instant completedAt;

    protected IdempotencyRecord() {
        // Make JPA happy
    }

    /** A fresh reservation, before the request it guards has actually been handled. */
    IdempotencyRecord(String id, String fingerprint, Instant createdAt) {
        this.id = id;
        this.fingerprint = fingerprint;
        this.status = IdempotencyStatus.RESERVED;
        this.createdAt = createdAt;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }

    void markCompleted(int responseStatus, String responseHeaders, byte[] responseBody, Instant completedAt) {
        this.status = IdempotencyStatus.COMPLETED;
        this.responseStatus = responseStatus;
        this.responseHeaders = responseHeaders;
        this.responseBody = responseBody;
        this.completedAt = completedAt;
    }

    boolean fingerprintMatches(String otherFingerprint) {
        return fingerprint.equals(otherFingerprint);
    }

    boolean isStaleReservation(Instant now, Duration reservationTimeout) {
        return status == IdempotencyStatus.RESERVED && createdAt.plus(reservationTimeout).isBefore(now);
    }

    IdempotencyStatus getStatus() {
        return status;
    }

    /** Public: {@code IdempotencyFilter} (in {@code shared.web}) reads these back to replay a stored response. */
    public int getResponseStatus() {
        if (responseStatus == null) {
            throw new IllegalStateException("Record " + id + " has not been completed yet");
        }
        return responseStatus;
    }

    public String getResponseHeaders() {
        if (responseHeaders == null) {
            throw new IllegalStateException("Record " + id + " has not been completed yet");
        }
        return responseHeaders;
    }

    public byte[] getResponseBody() {
        if (responseBody == null) {
            throw new IllegalStateException("Record " + id + " has not been completed yet");
        }
        return responseBody;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
