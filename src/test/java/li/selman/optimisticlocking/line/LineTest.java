package li.selman.optimisticlocking.line;

import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
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
}
