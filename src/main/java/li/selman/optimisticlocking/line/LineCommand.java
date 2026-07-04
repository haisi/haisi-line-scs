package li.selman.optimisticlocking.line;

public sealed interface LineCommand {

    record CreateLine(LineId id, int left, int right) implements LineCommand {}
    record DeleteLine() implements LineCommand {}
    record MovePoint(Side side, int by) implements LineCommand {
        public enum Side { LEFT, RIGHT }
    }

}
