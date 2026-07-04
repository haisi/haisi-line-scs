package li.selman.optimisticlocking.line;

import jakarta.persistence.Embeddable;
import java.util.UUID;
import org.jmolecules.ddd.types.Identifier;

@Embeddable
public record LineId(UUID id) implements Identifier {

    public static LineId of(UUID id) {
        return new LineId(id);
    }

    @Override
    public String toString() {
        return id.toString();
    }
}
