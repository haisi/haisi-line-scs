package li.selman.optimisticlocking.line;

import li.selman.optimisticlocking.shared.Command;

public sealed interface LineCommand extends Command {

    record CreateLine(LineId id, int left, int right) implements LineCommand {}
    record DeleteLine() implements LineCommand {}
    record MovePoint(Side side, int by) implements LineCommand {
        public enum Side { LEFT, RIGHT }
    }

}
