package li.selman.optimisticlocking.line.web;

import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.linkTo;
import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.methodOn;

import java.util.List;
import li.selman.optimisticlocking.line.Line;
import li.selman.optimisticlocking.line.LineAuthorization;
import li.selman.optimisticlocking.line.LineCommand;
import li.selman.optimisticlocking.line.ManualOperation;
import li.selman.optimisticlocking.line.ManualOperationRepository;
import li.selman.optimisticlocking.shared.IfMatch;
import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.Link;
import org.springframework.hateoas.LinkRelation;
import org.springframework.hateoas.RepresentationModel;
import org.springframework.hateoas.mediatype.hal.HalModelBuilder;
import org.springframework.hateoas.server.EntityLinks;
import org.springframework.stereotype.Component;

/**
 * Builds the HAL representation of a {@link Line}: {@code self}/{@code delete} at the top level,
 * plus {@code leftPoint}/{@code rightPoint} embedded as their own sub-resources, each carrying its
 * own {@code moveLeft}/{@code moveRight} affordance links, and {@code operations} -- the line's
 * {@link ManualOperation} audit trail. Replaces the older {@code LineRepresentationModelProcessor},
 * whose generic, {@code AggregateCommands}-driven loop stopped fitting once moves became per-point
 * rather than a single top-level relation per command.
 */
@Component
class LineModelAssembler {

    private final EntityLinks entityLinks;
    private final LineAuthorization lineAuthorization;
    private final ManualOperationRepository manualOperationRepository;

    LineModelAssembler(
            EntityLinks entityLinks,
            LineAuthorization lineAuthorization,
            ManualOperationRepository manualOperationRepository) {
        this.entityLinks = entityLinks;
        this.lineAuthorization = lineAuthorization;
        this.manualOperationRepository = manualOperationRepository;
    }

    RepresentationModel<?> toModel(Line line) {
        return HalModelBuilder.halModelOf(new LineSummary(line.getId(), line.getBusinessPartnerId()))
                .links(topLevelLinks(line))
                .embed(leftPointModel(line), LinkRelation.of("leftPoint"))
                .embed(rightPointModel(line), LinkRelation.of("rightPoint"))
                .embed(operations(line), LinkRelation.of("operations"))
                .build();
    }

    private List<ManualOperationRepresentation> operations(Line line) {
        return manualOperationRepository
                .findByLineIdOrderByPerformedAtAsc(line.getId().id())
                .stream()
                .map(op -> new ManualOperationRepresentation(
                        op.getOperation(), op.getDetail(), op.getPerformedBy(), op.getPerformedAt()))
                .toList();
    }

    private List<Link> topLevelLinks(Line line) {
        Link self = entityLinks.linkForItemResource(Line.class, line.getId()).withSelfRel();
        boolean canDelete =
                line.can(LineCommand.DeleteLine.class) && lineAuthorization.can(LineCommand.DeleteLine.class, line);
        return canDelete
                ? List.of(
                        self,
                        linkTo(methodOn(LineController.class).delete(line.getId(), IfMatch.of(null)))
                                .withRel("delete"))
                : List.of(self);
    }

    private EntityModel<PointRepresentation> leftPointModel(Line line) {
        EntityModel<PointRepresentation> model = EntityModel.of(new PointRepresentation(
                line.getLeftPoint().getId(),
                line.getLeftPoint().getPosition(),
                line.getLeftPoint().getNumberOfUpdates()));
        boolean authorized = lineAuthorization.can(LineCommand.MoveLeft.class, line);
        model.addIf(authorized && line.canMoveLeftPointLeft(), () -> linkTo(methodOn(LineController.class)
                        .moveLeftPointLeft(line.getId(), IfMatch.of(null), null, null, new MoveRequest(1)))
                .withRel("moveLeft"));
        model.addIf(authorized && line.canMoveLeftPointRight(), () -> linkTo(methodOn(LineController.class)
                        .moveLeftPointRight(line.getId(), IfMatch.of(null), null, null, new MoveRequest(1)))
                .withRel("moveRight"));
        return model;
    }

    private EntityModel<PointRepresentation> rightPointModel(Line line) {
        EntityModel<PointRepresentation> model = EntityModel.of(new PointRepresentation(
                line.getRightPoint().getId(),
                line.getRightPoint().getPosition(),
                line.getRightPoint().getNumberOfUpdates()));
        boolean authorized = lineAuthorization.can(LineCommand.MoveRight.class, line);
        model.addIf(authorized && line.canMoveRightPointLeft(), () -> linkTo(methodOn(LineController.class)
                        .moveRightPointLeft(line.getId(), IfMatch.of(null), null, null, new MoveRequest(1)))
                .withRel("moveLeft"));
        model.addIf(authorized && line.canMoveRightPointRight(), () -> linkTo(methodOn(LineController.class)
                        .moveRightPointRight(line.getId(), IfMatch.of(null), null, null, new MoveRequest(1)))
                .withRel("moveRight"));
        return model;
    }
}
