package li.selman.optimisticlocking.line.web;

import li.selman.optimisticlocking.line.Line;
import li.selman.optimisticlocking.line.LineAuthorization;
import li.selman.optimisticlocking.line.LineCommand;
import li.selman.optimisticlocking.shared.AggregateCommands;
import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.IanaLinkRelations;
import org.springframework.hateoas.server.EntityLinks;
import org.springframework.hateoas.server.RepresentationModelProcessor;
import org.springframework.stereotype.Component;

@Component
class LineRepresentationModelProcessor implements RepresentationModelProcessor<EntityModel<Line>> {

    private final EntityLinks entityLinks;
    private final LineAuthorization lineAuthorization;
    private final AggregateCommands<Line, LineCommand> aggregateCommands =
            new AggregateCommands<>(Line.class, LineCommand.class);

    LineRepresentationModelProcessor(EntityLinks entityLinks, LineAuthorization lineAuthorization) {
        this.entityLinks = entityLinks;
        this.lineAuthorization = lineAuthorization;
    }

    @Override
    public EntityModel<Line> process(EntityModel<Line> model) {
        Line content = model.getContent();

        model.addIf(!model.hasLink(IanaLinkRelations.SELF), () -> entityLinks
                .linkForItemResource(Line.class, content.getId())
                .withSelfRel());

        aggregateCommands.getCommands().forEach(command -> addCommandLink(model, content, command));
        return model;
    }

    private void addCommandLink(EntityModel<Line> model, Line line, Class<? extends LineCommand> commandType) {
        String rel = aggregateCommands.getRel(commandType);
        model.addIf(
                line.can(commandType) && lineAuthorization.can(commandType, line),
                () -> entityLinks.linkFor(Line.class).slash(rel).withRel(rel));
    }
}
