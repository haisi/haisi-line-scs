package li.selman.optimisticlocking.line.web;

import li.selman.optimisticlocking.line.Line;
import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.IanaLinkRelations;
import org.springframework.hateoas.server.EntityLinks;
import org.springframework.hateoas.server.RepresentationModelProcessor;
import org.springframework.stereotype.Component;

import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.linkTo;
import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.methodOn;

@Component
class LineRepresentationModelProcessor implements RepresentationModelProcessor<EntityModel<Line>> {

    private final EntityLinks entityLinks;

    LineRepresentationModelProcessor(EntityLinks entityLinks) {
        this.entityLinks = entityLinks;
    }

    @Override
    public EntityModel<Line> process(EntityModel<Line> model) {
        Line content = model.getContent();
        model.addIf(!model.hasLink(IanaLinkRelations.SELF),
                () -> entityLinks.linkForItemResource(Line.class, content.getId()).withSelfRel());
        model.addIf(content.canMoveLeft(),
                () -> linkTo(methodOn(LineController.class).moveLeft(content.getId(), null, null, null))
                        .withRel("move-left"));
        model.addIf(content.canMoveRight(),
                () -> linkTo(methodOn(LineController.class).moveRight(content.getId(), null, null, null))
                        .withRel("move-right"));
        return model;
    }
}
