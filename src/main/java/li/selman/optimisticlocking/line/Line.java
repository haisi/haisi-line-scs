package li.selman.optimisticlocking.line;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.Instant;
import li.selman.optimisticlocking.shared.BusinessRuleViolated;
import org.jmolecules.architecture.onion.simplified.DomainRing;
import org.jmolecules.ddd.types.AggregateRoot;
import org.jspecify.annotations.Nullable;

@DomainRing
@Entity
public class Line implements AggregateRoot<Line, LineId> {

    private static final int MAX_UPDATES = 5;

    @EmbeddedId
    private LineId id;

    @Version
    private long lockVersion = 1;

    @OneToOne(cascade = CascadeType.ALL, orphanRemoval = true)
    private LeftPoint left;

    @OneToOne(cascade = CascadeType.ALL, orphanRemoval = true)
    private RightPoint right;

    @Column
    private @Nullable Instant updatedAt;

    @Column(nullable = false)
    private String businessPartnerId;

    protected Line() {
        // Make JPA happy
    }

    Line(LineId id, int left, int right, String businessPartnerId) {
        if (left > right) throw new BusinessRuleViolated("left (%d) would exceed right (%d)".formatted(left, right));
        this.id = id;
        this.left = new LeftPoint(left);
        this.right = new RightPoint(right);
        this.businessPartnerId = businessPartnerId;
    }

    @Override
    public LineId getId() {
        return id;
    }

    // Already surfaced via the ETag header (see LineController); not duplicated in the body.
    @JsonIgnore
    public String getLockVersion() {
        return "\"" + lockVersion + "\"";
    }

    public int getLeft() {
        return left.getPosition();
    }

    public int getRight() {
        return right.getPosition();
    }

    public String getBusinessPartnerId() {
        return businessPartnerId;
    }

    public boolean sameAs(int left, int right, String businessPartnerId) {
        return this.left.getPosition() == left
                && this.right.getPosition() == right
                && this.businessPartnerId.equals(businessPartnerId);
    }

    public void moveLeft(int by) {
        int newLeft = left.getPosition() + by;
        if (newLeft < 0) {
            throw new BusinessRuleViolated("left point may not go below zero");
        }
        validateInvariants(newLeft, right.getPosition());
        left.move(by);
    }

    public void moveRight(int by) {
        int newRight = right.getPosition() + by;
        validateInvariants(left.getPosition(), newRight);
        right.move(by);
    }

    private void validateInvariants(int leftPosition, int rightPosition) {
        if (leftPosition > rightPosition) {
            throw new BusinessRuleViolated("left (%d) would exceed right (%d)".formatted(leftPosition, rightPosition));
        }
        if (totalUpdates() >= MAX_UPDATES) {
            throw new BusinessRuleViolated("line may only be updated %d times".formatted(MAX_UPDATES));
        }
    }

    private int totalUpdates() {
        return left.getNumberOfUpdates() + right.getNumberOfUpdates();
    }

    /**
     * Determines whether the <b>typ of command</b> would be allowed.
     * Important, if the user tries to actually execute the command, you must still <b>check the content of the command</b>.
     * At view time these values are not available yet!
     */
    public boolean can(Class<? extends LineCommand> commandType) {
        if (commandType == LineCommand.CreateLine.class) {
            return true;
        } else if (commandType == LineCommand.DeleteLine.class) {
            return true; // no business invariant blocks a delete; line_#delete is enforced in the web layer
        } else if (commandType == LineCommand.MoveLeft.class) {
            return left.getPosition() > 0 && totalUpdates() < MAX_UPDATES;
        } else if (commandType == LineCommand.MoveRight.class) {
            return totalUpdates() < MAX_UPDATES;
        }
        throw new IllegalStateException("No 'can' check for " + commandType);
    }
}
