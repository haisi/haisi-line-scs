package li.selman.optimisticlocking.line;

import jakarta.persistence.*;
import org.jmolecules.architecture.onion.simplified.DomainRing;

@DomainRing
@Entity
public class LeftPoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @SuppressWarnings("NullAway.Init")
    private Long id;

    @Column(nullable = false)
    private int position;

    @Column(nullable = false)
    private int numberOfUpdates;

    // NOTE: No @Version, as this is a child entity of an aggregate root. Consistency boundary at aggregate level.

    protected LeftPoint() {
        // Make JPA happy
    }

    public LeftPoint(int position) {
        this.position = position;
        this.numberOfUpdates = 0;
    }

    void move(int by) {
        if (position + by < 0) {
            throw new IllegalStateException("Left point may not be below zero");
        }

        position = position + by;
        numberOfUpdates++;
    }

    public Long getId() {
        return id;
    }

    public int getPosition() {
        return position;
    }

    public int getNumberOfUpdates() {
        return numberOfUpdates;
    }
}
