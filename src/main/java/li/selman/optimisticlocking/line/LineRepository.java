package li.selman.optimisticlocking.line;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface LineRepository extends Repository<Line, LineId> {

    Line save(Line line);

    Optional<Line> findById(LineId id);

    void delete(Line line);

    /**
     * Loads for a mutation that only touches a child (Left/RightPoint): forces the root's
     * version to bump at flush even though Line's own mapped state didn't change, so the
     * aggregate-level optimistic lock still catches the conflict.
     */
    @Lock(LockModeType.OPTIMISTIC_FORCE_INCREMENT)
    @Query("select l from Line l where l.id = :id")
    Optional<Line> findForUpdate(@Param("id") LineId id);
}
