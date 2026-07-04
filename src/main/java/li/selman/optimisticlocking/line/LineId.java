package li.selman.optimisticlocking.line;

import jakarta.persistence.Embeddable;
import org.jmolecules.ddd.types.Identifier;

import java.util.UUID;

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
