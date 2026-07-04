package li.selman.optimisticlocking.line;

import jakarta.transaction.Transactional;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import li.selman.optimisticlocking.shared.IfMatch;
import li.selman.optimisticlocking.shared.PreconditionRequired;
import li.selman.optimisticlocking.shared.StaleStateIdentified;
import li.selman.optimisticlocking.shared.idempotency.IdempotencyKey;
import li.selman.optimisticlocking.shared.idempotency.IdempotencyKeyRepository;
import li.selman.optimisticlocking.shared.idempotency.IdempotencyKeyReused;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
public class LineService {

    private final LineRepository repo;
    private final IdempotencyKeyRepository idempotencyKeyRepository;

    LineService(LineRepository repo, IdempotencyKeyRepository idempotencyKeyRepository) {
        this.repo = repo;
        this.idempotencyKeyRepository = idempotencyKeyRepository;
    }

    @Transactional
    public void delete(LineId id, IfMatch ifMatch) {
        Line line = repo.findById(id).orElse(null);
        if (line == null) return; // already gone -> 204
        if (!ifMatch.isAbsent() && !ifMatch.matches(line.getLockVersion())) {
            // In case the user only want to delete the resource, iff it has not changed yet
            throw new StaleStateIdentified(id.id()); // 412
        }
        repo.delete(line);
    }

    /**
     * PUT-create: the client mints the id, so a retry with identical content is naturally a
     * no-op (no idempotency-key table needed here, per the talk's "freebie" pattern). A retry
     * with different content for an existing id has stated no precondition, so it's a 409, not
     * a 412.
     */
    @Transactional
    public LineCreationResult create(LineCommand.CreateLine cmd) {
        Optional<Line> existing = repo.findById(cmd.id());
        if (existing.isPresent()) {
            Line line = existing.get();
            if (line.sameAs(cmd.left(), cmd.right())) {
                return new LineCreationResult(line, false); // 200 OK: identical replay
            }
            throw new LineAlreadyExists(cmd.id()); // 409 Conflict
        }
        Line line = new Line(cmd.id(), cmd.left(), cmd.right());
        repo.save(line);
        return new LineCreationResult(line, true); // 201 Created
    }

    @Transactional
    public Line moveLeft(LineId id, IfMatch ifMatch, int by, @Nullable String idempotencyKey) {
        return move(id, new LineCommand.MoveLeft(by), ifMatch, idempotencyKey, line -> line.moveLeft(by));
    }

    @Transactional
    public Line moveRight(LineId id, IfMatch ifMatch, int by, @Nullable String idempotencyKey) {
        return move(id, new LineCommand.MoveRight(by), ifMatch, idempotencyKey, line -> line.moveRight(by));
    }

    /**
     * Gate order mirrors the talk's "Order matters" pipeline exactly:
     * 1. Idempotency-Key: a recognised retry short-circuits here, skipping every gate below.
     * 2. Precondition: If-Match is mandatory for a move (428 if absent, 412 if stale).
     * 3. Business invariants, enforced on the aggregate root (422 on violation).
     * 4. Version CAS at commit, via the forced-increment read (409 on a lost race).
     * The idempotency record is staged in the same transaction as the mutation, so a lost CAS
     * rolls back both together -- a failed attempt never leaves behind a replay record.
     */
    private Line move(
            LineId id, LineCommand command, IfMatch ifMatch, @Nullable String idempotencyKey, Consumer<Line> apply) {
        String fingerprint = command.toString();
        if (idempotencyKey != null) {
            UUID key = UUID.fromString(idempotencyKey);
            Optional<IdempotencyKey> priorAttempt = idempotencyKeyRepository.findById(key);
            if (priorAttempt.isPresent()) {
                if (!priorAttempt.get().getFingerprint().equals(fingerprint)) {
                    throw new IdempotencyKeyReused(key); // 422 Unprocessable Content
                }
                return repo.findById(id).orElseThrow(() -> new LineNotFound(id.id())); // replay
            }
        }

        if (ifMatch.isAbsent()) {
            throw new PreconditionRequired(id.id()); // 428 Precondition Required
        }

        Line line = repo.findForUpdate(id).orElseThrow(() -> new LineNotFound(id.id()));
        if (!ifMatch.matches(line.getLockVersion())) {
            throw new StaleStateIdentified(id.id()); // 412 Precondition Failed
        }

        apply.accept(line); // 422 on invariant violation

        if (idempotencyKey != null) {
            idempotencyKeyRepository.save(new IdempotencyKey(UUID.fromString(idempotencyKey), fingerprint));
        }
        return line; // commit performs the forced-increment CAS -> 409 on a lost race
    }
}
