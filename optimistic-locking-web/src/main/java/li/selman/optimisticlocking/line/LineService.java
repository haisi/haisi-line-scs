package li.selman.optimisticlocking.line;

import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import li.selman.optimisticlocking.shared.IfMatch;
import li.selman.optimisticlocking.shared.PreconditionRequired;
import li.selman.optimisticlocking.shared.StaleStateIdentified;
import li.selman.optimisticlocking.shared.idempotency.IdempotencyKey;
import li.selman.optimisticlocking.shared.idempotency.IdempotencyKeyRepository;
import li.selman.optimisticlocking.shared.idempotency.IdempotencyKeyReused;
import org.jmolecules.architecture.onion.simplified.ApplicationRing;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@ApplicationRing
@Service
public class LineService {

    private final LineRepository repo;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final ManualOperationRepository manualOperationRepository;
    private final LineAuthorization authorization;

    LineService(
            LineRepository repo,
            IdempotencyKeyRepository idempotencyKeyRepository,
            ManualOperationRepository manualOperationRepository,
            LineAuthorization authorization) {
        this.repo = repo;
        this.idempotencyKeyRepository = idempotencyKeyRepository;
        this.manualOperationRepository = manualOperationRepository;
        this.authorization = authorization;
    }

    @Transactional
    public void delete(LineId id, IfMatch ifMatch) {
        authorization.assertCanDelete(); // line_#delete, user-independent: 403 if missing
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
        authorization.assertCanCreate(cmd.businessPartnerId()); // line_#create for that partner: 403 if missing
        Optional<Line> existing = repo.findById(cmd.id());
        if (existing.isPresent()) {
            Line line = existing.get();
            if (line.sameAs(cmd.left(), cmd.right(), cmd.businessPartnerId())) {
                return new LineCreationResult(line, false); // 200 OK: identical replay
            }
            throw new LineAlreadyExists(cmd.id()); // 409 Conflict
        }
        Line line = new Line(cmd.id(), cmd.left(), cmd.right(), cmd.businessPartnerId());
        repo.save(line);
        return new LineCreationResult(line, true); // 201 Created
    }

    @Transactional
    public Line moveLeft(LineId id, IfMatch ifMatch, int by, @Nullable String idempotencyKey) {
        LineCommand.MoveLeft command = new LineCommand.MoveLeft(by);
        return move(id, command, "left", by, ifMatch, idempotencyKey, line -> line.moveLeft(command));
    }

    @Transactional
    public Line moveRight(LineId id, IfMatch ifMatch, int by, @Nullable String idempotencyKey) {
        LineCommand.MoveRight command = new LineCommand.MoveRight(by);
        return move(id, command, "right", by, ifMatch, idempotencyKey, line -> line.moveRight(command));
    }

    /**
     * Gate order mirrors the talk's "Order matters" pipeline, with edit permission (data
     * authorization) checked as soon as the line is loaded, before its version is even inspected:
     * 1. Idempotency-Key: a recognised retry short-circuits here, skipping every gate below.
     * 2. Precondition: If-Match is mandatory for a move (428 if absent, 412 if stale).
     * 3. Edit permission: only a caller holding line_#create for the line's own business partner
     *    may move it (403 otherwise) -- re-checked against the line's stored businessPartnerId,
     *    not the caller-supplied X-Partner-Id header, same defense-in-depth create already gets.
     * 4. Business invariants, enforced on the aggregate root (422 on violation).
     * 5. Version CAS at commit, via the forced-increment read (409 on a lost race).
     * The idempotency record -- and, alongside it, the {@link ManualOperation} audit entry -- is
     * staged in the same transaction as the mutation, so a lost CAS rolls all three back together.
     * A recognised idempotency-key replay (gate 1) returns before either write, since it reports
     * the same logical move again rather than performing (or recording) a new one.
     */
    private Line move(
            LineId id,
            LineCommand command,
            String side,
            int by,
            IfMatch ifMatch,
            @Nullable String idempotencyKey,
            Consumer<Line> apply) {
        String fingerprint = command.toString();
        if (idempotencyKey != null) {
            UUID key = UUID.fromString(idempotencyKey);
            Optional<IdempotencyKey> priorAttempt = idempotencyKeyRepository.findById(key);
            if (priorAttempt.isPresent()) {
                if (!priorAttempt.get().getFingerprint().equals(fingerprint)) {
                    throw new IdempotencyKeyReused(key); // 422 Unprocessable Content
                }
                Line replay = repo.findById(id).orElseThrow(() -> new LineNotFound(id.id()));
                authorization.assertCanEdit(replay);
                return replay;
            }
        }

        if (ifMatch.isAbsent()) {
            throw new PreconditionRequired(id.id()); // 428 Precondition Required
        }

        Line line = repo.findForUpdate(id).orElseThrow(() -> new LineNotFound(id.id()));
        authorization.assertCanEdit(line); // only line_#create for the line's own partner may move it
        if (!ifMatch.matches(line.getLockVersion())) {
            throw new StaleStateIdentified(id.id()); // 412 Precondition Failed
        }

        apply.accept(line); // 422 on invariant violation
        manualOperationRepository.save(new ManualOperation(
                id.id(),
                command.name(),
                "%s point moved by %d".formatted(side, by),
                authorization.currentSubject(),
                Instant.now()));

        if (idempotencyKey != null) {
            idempotencyKeyRepository.save(new IdempotencyKey(UUID.fromString(idempotencyKey), fingerprint));
        }
        return line; // commit performs the forced-increment CAS -> 409 on a lost race
    }
}
