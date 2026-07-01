package li.selman.optimisticlocking.line;

import jakarta.persistence.*;
import org.jmolecules.ddd.types.AggregateRoot;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

@Entity
public class Line implements AggregateRoot<Line, LineId> {

    @EmbeddedId
    private LineId id;

    @Version
    private long lockVersion;

    @OneToOne(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "left_id")
    private LeftPoint left;

    @OneToOne(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "right_id")
    private RightPoint right;

    @Column
    private @Nullable Instant updatedAt;

    protected Line() {
        // Make JPA happy
    }

    Line(LineId id, int left, int right) {
        if (left > right) throw new IllegalArgumentException("left (%d) > right (%d)".formatted(left, right));
        this.id = id;
        this.left = new LeftPoint(left);
        this.right = new RightPoint(right);
    }

    public LineId getId() {
        return id;
    }

    public String getLockVersion() {
        return "\"" + lockVersion + "\"";
    }
}
