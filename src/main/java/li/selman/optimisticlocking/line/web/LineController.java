package li.selman.optimisticlocking.line.web;

import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.linkTo;
import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.methodOn;

import io.github.adr.linked.ADR;
import jakarta.annotation.Nullable;

import java.util.List;

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

    /**
     * BusinessPartner can only view their own lines. Users with the <b>user-role</b> can view all lines.
     *
     * @param partnerId one user can be part of multiple business partner. An operation however is done in the context
     *                  of a single business partner. The user defines which partner to use by passing the X-Partner-Id header.
     *                  null for AdBAZG.
     */
    @GetMapping("{id}")
    @PreAuthorize("hasRoleForPartner(@lineAuthorization.READ_ROLE, #partnerId)")
    @ADR(1)
    ResponseEntity<EntityModel<Line>> get(@PathVariable LineId id, IfNoneMatch ifNoneMatch,
                                          @RequestHeader(name = "X-Partner-Id", required = false) @Nullable String partnerId) {
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


    /**
     * BusinessPartner can only view their own lines. Users with the <b>user-role</b> can view all lines.
     *
     * <p>{@code X-Partner-Id} is mandatory for a business-partner caller (only {@code null} for a
     * user-independent caller like AdBAZG): without it, {@code hasRoleForPartner} has no partner to
     * check the role against and denies the request, so there is no fallback that leaks a partner's
     * lines to a caller who never named it.
     */
    @GetMapping
    @PreAuthorize("hasRoleForPartner(@lineAuthorization.READ_ROLE, #partnerId)")
    @ADR(1)
    ResponseEntity<Page<Line>> getAll(
            Pageable pageable, @RequestHeader(name = "X-Partner-Id", required = false) @Nullable String partnerId) {
        // partnerId == null only ever reaches here for a caller holding READ_ROLE user-independently
        // (@PreAuthorize already denied every other null-partnerId caller above).
        Page<Line> lines = partnerId != null
                ? lineRepository.findAllByBusinessPartnerIdIn(List.of(partnerId), pageable)
                : lineRepository.findAll(pageable);
        return ResponseEntity.ok(lines);
    }

    @DeleteMapping("{id}")
    // Delete is only allowed by user-role, i.e. by AdBAZG, not by BusinessPartner
    @PreAuthorize("hasRoleForAllPartners(@lineAuthorization.DELETE_ROLE)")
    ResponseEntity<Void> delete(@PathVariable LineId id, IfMatch ifMatch) {
        lineService.delete(id, ifMatch);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("{id}")
    @PreAuthorize("hasRoleForPartner(@lineAuthorization.CREATE_ROLE, #partnerId)")
    @ADR(1)
    ResponseEntity<EntityModel<Line>> create(@PathVariable LineId id, @RequestBody CreateLineRequest body, @RequestHeader(name = "X-Partner-Id", required = false) @Nullable String partnerId) {
        LineCreationResult result =
                lineService.create(new LineCommand.CreateLine(id, body.left(), body.right(), body.businessPartnerId()));
        // RFC 9110 §9.3.4: a 201 response MUST carry Location; a 200 replay needs none, since the
        // client already addressed this exact URI to get here.
        ResponseEntity.BodyBuilder response = result.created()
                ? ResponseEntity.status(HttpStatus.CREATED)
                .location(linkTo(methodOn(LineController.class).get(id, IfNoneMatch.of(null), null))
                        .toUri())
                : ResponseEntity.status(HttpStatus.OK);
        return response.eTag(result.line().getLockVersion()).body(EntityModel.of(result.line()));
    }

    @PutMapping("{id}/left")
    @PreAuthorize("hasRoleForPartner(@lineAuthorization.CREATE_ROLE, #partnerId)")
    @ADR(1)
    ResponseEntity<EntityModel<Line>> moveLeft(
            @PathVariable LineId id,
            IfMatch ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) @Nullable String idempotencyKey,
            @RequestHeader(name = "X-Partner-Id", required = false) @Nullable String partnerId,
            @RequestBody MoveRequest body) {
        Line line = lineService.moveLeft(id, ifMatch, body.by(), idempotencyKey);
        return ResponseEntity.ok().eTag(line.getLockVersion()).body(EntityModel.of(line));
    }

    @PutMapping("{id}/right")
    @PreAuthorize("hasRoleForPartner(@lineAuthorization.CREATE_ROLE, #partnerId)")
    @ADR(1)
    ResponseEntity<EntityModel<Line>> moveRight(
            @PathVariable LineId id,
            IfMatch ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) @Nullable String idempotencyKey,
            @RequestHeader(name = "X-Partner-Id", required = false) @Nullable String partnerId,
            @RequestBody MoveRequest body) {
        Line line = lineService.moveRight(id, ifMatch, body.by(), idempotencyKey);
        return ResponseEntity.ok().eTag(line.getLockVersion()).body(EntityModel.of(line));
    }
}
