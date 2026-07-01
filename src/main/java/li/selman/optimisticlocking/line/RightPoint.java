package li.selman.optimisticlocking.line;

import jakarta.persistence.*;

@Entity
public class RightPoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private int position;

    @Column(nullable = false)
    private int numberOfUpdates;

    // NOTE: No @Version, as this is a child entity of an aggregate root. Consistency boundary at aggregate level.

    protected RightPoint() {
        // Make JPA happy
    }

    public RightPoint(int position) {
        this.position = position;
        this.numberOfUpdates = 0;
    }

    void move(int by) {
        position = position + by;
        numberOfUpdates++;
    }

    int getPosition() {
        return position;
    }

    int getNumberOfUpdates() {
        return numberOfUpdates;
    }
}
