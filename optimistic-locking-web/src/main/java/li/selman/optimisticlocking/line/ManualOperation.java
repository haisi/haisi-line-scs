package li.selman.optimisticlocking.line;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.jmolecules.architecture.onion.simplified.DomainRing;

/**
 * An append-only log of manual operations a user performed on a line -- deliberately generic
 * (an {@code operation} name plus a free-text {@code detail}) rather than shaped around moves
 * specifically, so a future operation type needs no schema or entity change. {@code lineId} is a
 * plain column, not a JPA relationship or FK to {@link Line} -- mirrors {@code IdempotencyKey}'s
 * "should outlive the line it applied to" reasoning (see {@code schema.sql}): an audit trail must
 * survive the line it describes being deleted.
 */
@DomainRing
@Entity
@Table(name = "manual_operation")
public class ManualOperation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @SuppressWarnings("NullAway.Init")
    private Long id;

    @Column(name = "line_id", nullable = false)
    private UUID lineId;

    @Column(nullable = false)
    private String operation;

    @Column(nullable = false)
    private String detail;

    @Column(name = "performed_by", nullable = false)
    private String performedBy;

    @Column(name = "performed_at", nullable = false)
    private Instant performedAt;

    protected ManualOperation() {
        // Make JPA happy
    }

    ManualOperation(UUID lineId, String operation, String detail, String performedBy, Instant performedAt) {
        this.lineId = lineId;
        this.operation = operation;
        this.detail = detail;
        this.performedBy = performedBy;
        this.performedAt = performedAt;
    }

    public String getOperation() {
        return operation;
    }

    public String getDetail() {
        return detail;
    }

    public String getPerformedBy() {
        return performedBy;
    }

    public Instant getPerformedAt() {
        return performedAt;
    }
}
