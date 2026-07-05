package li.selman.optimisticlocking.line.web;

import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.linkTo;
import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.methodOn;

import jakarta.annotation.Nullable;
import java.util.Set;
import li.selman.optimisticlocking.line.Line;
import li.selman.optimisticlocking.line.LineAuthorization;
import li.selman.optimisticlocking.line.LineCommand;
import li.selman.optimisticlocking.line.LineCreationResult;
import li.selman.optimisticlocking.line.LineId;
import li.selman.optimisticlocking.line.LineRepository;
import li.selman.optimisticlocking.line.LineService;
import li.selman.optimisticlocking.shared.IfMatch;
import li.selman.optimisticlocking.shared.IfNoneMatch;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.server.ExposesResourceFor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("lines")
@ExposesResourceFor(Line.class)
public class LineController {

    private final LineRepository lineRepository;
    private final LineService lineService;
    private final LineAuthorization lineAuthorization;

    public LineController(LineRepository lineRepository, LineService lineService, LineAuthorization lineAuthorization) {
        this.lineRepository = lineRepository;
        this.lineService = lineService;
        this.lineAuthorization = lineAuthorization;
    }

    @GetMapping("{id}")
    @PreAuthorize("hasRole(@lineAuthorization.READ_ROLE)")
    ResponseEntity<EntityModel<Line>> get(@PathVariable LineId id, IfNoneMatch ifNoneMatch) {
        return lineRepository
                .findById(id)
                .map(it -> {
                    lineAuthorization.requireOwnership(it); // only the creating business partner may view
                    if (ifNoneMatch.matches(it.getLockVersion())) {
                        return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                                .eTag(it.getLockVersion())
                                .<EntityModel<Line>>build();
                    }
                    return ResponseEntity.ok().eTag(it.getLockVersion()).body(EntityModel.of(it));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("{id}")
    @PreAuthorize("hasRole(@lineAuthorization.DELETE_ROLE)")
    ResponseEntity<Void> delete(@PathVariable LineId id, IfMatch ifMatch) {
        lineService.delete(id, ifMatch);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("{id}")
    @PreAuthorize("hasRole(@lineAuthorization.CREATE_ROLE)")
    ResponseEntity<EntityModel<Line>> create(@PathVariable LineId id, @RequestBody CreateLineRequest body) {
        LineCreationResult result =
                lineService.create(new LineCommand.CreateLine(id, body.left(), body.right(), body.businessPartnerId()));
        // RFC 9110 §9.3.4: a 201 response MUST carry Location; a 200 replay needs none, since the
        // client already addressed this exact URI to get here.
        ResponseEntity.BodyBuilder response = result.created()
                ? ResponseEntity.status(HttpStatus.CREATED)
                        .location(linkTo(methodOn(LineController.class).get(id, IfNoneMatch.of(null)))
                                .toUri())
                : ResponseEntity.status(HttpStatus.OK);
        return response.eTag(result.line().getLockVersion()).body(EntityModel.of(result.line()));
    }

    @PutMapping("{id}/left")
    @PreAuthorize("hasRole(@lineAuthorization.CREATE_ROLE)")
    ResponseEntity<EntityModel<Line>> moveLeft(
            @PathVariable LineId id,
            IfMatch ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) @Nullable String idempotencyKey,
            @RequestBody MoveRequest body) {
        Line line = lineService.moveLeft(id, ifMatch, body.by(), idempotencyKey);
        return ResponseEntity.ok().eTag(line.getLockVersion()).body(EntityModel.of(line));
    }

    @PutMapping("{id}/right")
    @PreAuthorize("hasRole(@lineAuthorization.CREATE_ROLE)")
    ResponseEntity<EntityModel<Line>> moveRight(
            @PathVariable LineId id,
            IfMatch ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) @Nullable String idempotencyKey,
            @RequestBody MoveRequest body) {
        Line line = lineService.moveRight(id, ifMatch, body.by(), idempotencyKey);
        return ResponseEntity.ok().eTag(line.getLockVersion()).body(EntityModel.of(line));
    }

    @GetMapping
    @PreAuthorize(
            "hasRoleForPartner(@lineAuthorization.READ_ROLE, #partnerId) || hasRole(@lineAuthorization.READ_ROLE)")
    ResponseEntity<Page<Line>> getAll(
            Pageable pageable, @RequestHeader(name = "X-Partner-Id", required = false) @Nullable String partnerId) {
        Set<String> businessPartnerIds = lineAuthorization.currentBusinessPartnerIds();
        Page<Line> lines = businessPartnerIds.isEmpty()
                ? Page.empty(pageable)
                : lineRepository.findAllByBusinessPartnerIdIn(businessPartnerIds, pageable);
        return ResponseEntity.ok(lines);
    }
}
