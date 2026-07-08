package li.selman.optimisticlocking.line;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class LineTest {

    @Nested
    class Can {

        /**
         * {@link Line#can(Class)} switches on every permitted subclass of the sealed {@link LineCommand}
         * interface and throws {@link IllegalStateException} for any type it doesn't recognize. Unlike an
         * exhaustive {@code switch} over a sealed type, that if/else chain isn't checked by the compiler, so
         * nothing stops someone from adding a new {@code LineCommand} subtype without adding a matching
         * branch here. This test iterates every permitted subclass so that gap fails loudly in CI instead of
         * surfacing later as an unhandled 500 in production.
         */
        @ParameterizedTest
        @MethodSource("commandTypes")
        void commandTypeSupported(Class<? extends LineCommand> commandType) {
            // given - a regular line
            Line line = LineFixture.newBuilder().build();

            // when/then
            assertThatCode(() -> line.can(commandType)).doesNotThrowAnyException();
        }

        static Stream<Class<? extends LineCommand>> commandTypes() {
            return Arrays.stream(LineCommand.class.getPermittedSubclasses()).map(c -> c.asSubclass(LineCommand.class));
        }
    }

    /**
     * {@link Line#can(LineCommand)} is the content-aware counterpart to {@link Line#can(Class)}: it
     * looks at the actual {@code by} of a move, not just its type, so it can catch invariant
     * violations that only depend on the command's content.
     */
    @Nested
    class CanCommand {

        @Test
        void moveLeft_belowZero_notAllowed() {
            Line line = LineFixture.newBuilder().line(1, 5).build();

            assertThat(line.can(new LineCommand.MoveLeft(-2))).isFalse();
        }

        @Test
        void moveLeft_pastRight_notAllowed() {
            Line line = LineFixture.newBuilder().line(1, 5).build();

            assertThat(line.can(new LineCommand.MoveLeft(5))).isFalse();
        }

        @Test
        void moveLeft_withinBounds_allowed() {
            Line line = LineFixture.newBuilder().line(1, 5).build();

            assertThat(line.can(new LineCommand.MoveLeft(2))).isTrue();
        }

        @Test
        void moveRight_beforeLeft_notAllowed() {
            Line line = LineFixture.newBuilder().line(1, 5).build();

            assertThat(line.can(new LineCommand.MoveRight(-5))).isFalse();
        }

        @Test
        void moveRight_atMaxUpdates_notAllowed() {
            Line line =
                    LineFixture.newBuilder().line(1, 5).leftUpdates(3).rightUpdates(2).build();

            assertThat(line.can(new LineCommand.MoveRight(1))).isFalse();
        }

        @Test
        void createAndDelete_alwaysAllowed() {
            Line line = LineFixture.newBuilder().build();

            assertThat(line.can(new LineCommand.CreateLine(line.getId(), 1, 5, "acme")))
                    .isTrue();
            assertThat(line.can(new LineCommand.DeleteLine())).isTrue();
        }
    }
}
