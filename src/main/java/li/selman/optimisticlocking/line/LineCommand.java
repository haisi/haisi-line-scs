package li.selman.optimisticlocking.line;

import li.selman.optimisticlocking.shared.Command;
import org.springframework.hateoas.server.core.Relation;

public sealed interface LineCommand extends Command {

    record CreateLine(LineId id, int left, int right) implements LineCommand {}
    record DeleteLine() implements LineCommand {}
    @Relation("move-left") record MoveLeft(int by) implements LineCommand {}
    @Relation("move-right") record MoveRight(int by) implements LineCommand {}

}
