package li.selman.optimisticlocking.line.web;

import jakarta.annotation.Nullable;
import li.selman.optimisticlocking.line.Line;
import li.selman.optimisticlocking.line.LineCommand;
import li.selman.optimisticlocking.line.LineCreationResult;
import li.selman.optimisticlocking.line.LineId;
import li.selman.optimisticlocking.line.LineRepository;
import li.selman.optimisticlocking.line.LineService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.server.ExposesResourceFor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("lines")
@ExposesResourceFor(Line.class)
public class LineController {

    private final LineRepository lineRepository;
    private final LineService lineService;

    public LineController(LineRepository lineRepository, LineService lineService) {
        this.lineRepository = lineRepository;
        this.lineService = lineService;
    }

    @GetMapping("{id}")
    ResponseEntity<EntityModel<Line>> get(
            @PathVariable LineId id,
            @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) @Nullable String ifNoneMatch) {
        return lineRepository
                .findById(id)
                .map(it -> {
                    if (ifNoneMatch != null && ifNoneMatch.equals(it.getLockVersion())) {
                        return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                                .eTag(it.getLockVersion())
                                .<EntityModel<Line>>build();
                    }
                    return ResponseEntity.ok().eTag(it.getLockVersion()).body(EntityModel.of(it));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("{id}")
    ResponseEntity<Void> delete(
            @PathVariable LineId id,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) @Nullable String ifMatch) {
        lineService.delete(id, ifMatch);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("{id}")
    ResponseEntity<EntityModel<Line>> create(@PathVariable LineId id, @RequestBody CreateLineRequest body) {
        LineCreationResult result = lineService.create(new LineCommand.CreateLine(id, body.left(), body.right()));
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status)
                .eTag(result.line().getLockVersion())
                .body(EntityModel.of(result.line()));
    }

    @PutMapping("{id}/left")
    ResponseEntity<EntityModel<Line>> moveLeft(
            @PathVariable LineId id,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) @Nullable String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) @Nullable String idempotencyKey,
            @RequestBody MoveRequest body) {
        Line line = lineService.moveLeft(id, ifMatch, body.by(), idempotencyKey);
        return ResponseEntity.ok().eTag(line.getLockVersion()).body(EntityModel.of(line));
    }

    @PutMapping("{id}/right")
    ResponseEntity<EntityModel<Line>> moveRight(
            @PathVariable LineId id,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) @Nullable String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) @Nullable String idempotencyKey,
            @RequestBody MoveRequest body) {
        Line line = lineService.moveRight(id, ifMatch, body.by(), idempotencyKey);
        return ResponseEntity.ok().eTag(line.getLockVersion()).body(EntityModel.of(line));
    }

    @GetMapping
    ResponseEntity<Page<Line>> getAll(Pageable pageable) {
        return ResponseEntity.ok(lineRepository.findAll(pageable));
    }
}
