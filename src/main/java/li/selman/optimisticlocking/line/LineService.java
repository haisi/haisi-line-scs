package li.selman.optimisticlocking.line;

import jakarta.transaction.Transactional;
import li.selman.optimisticlocking.shared.StaleStateIdentified;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.UUID;

@Service
public class LineService {

    private final LineRepository repo;

    LineService(LineRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public void delete(LineId id, @Nullable String ifMatchVersion) {
        Line line = repo.findById(id).orElse(null);
        if (line == null) return;                // already gone -> 204
        if (ifMatchVersion != null && !Objects.equals(line.getLockVersion(), ifMatchVersion)) {
            // In case the user only want to delete the resource, iff it has not changed yet
            throw new StaleStateIdentified(id.id());  // 412
        }
        repo.delete(line);
    }

    @Transactional
    public Line create(LineCommand.CreateLine cmd) {
        return null;
    }
}
