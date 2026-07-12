package li.selman.optimisticlocking.line;

import java.util.List;
import java.util.UUID;
import org.jmolecules.architecture.onion.simplified.DomainRing;
import org.springframework.data.repository.Repository;

@DomainRing
public interface ManualOperationRepository extends Repository<ManualOperation, Long> {

    ManualOperation save(ManualOperation operation);

    List<ManualOperation> findByLineIdOrderByPerformedAtAsc(UUID lineId);
}
