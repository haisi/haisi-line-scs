package li.selman.optimisticlocking.line;

import org.springframework.data.repository.Repository;

import java.util.Optional;

public interface LineRepository extends Repository<Line, LineId> {

    Line save(Line line);

    Optional<Line> findById(LineId id);

    void delete(Line line);
}
