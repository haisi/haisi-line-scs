package li.selman.optimisticlocking.line;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import ch.admin.bit.jeap.security.resource.semanticAuthentication.SemanticApplicationRole;
import ch.admin.bit.jeap.security.test.resource.ServletSemanticAuthorizationMock;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class LineAuthorizationTest {

    private static final String SYSTEM = "wvs";
    private static final String ACME = "acme";
    private static final String OTHER_CORP = "other-corp";

    private static final SemanticApplicationRole CREATE = SemanticApplicationRole.builder()
            .system(SYSTEM)
            .resource("line")
            .operation("create")
            .build();
    private static final SemanticApplicationRole DELETE = SemanticApplicationRole.builder()
            .system(SYSTEM)
            .resource("line")
            .operation("delete")
            .build();
    private static final SemanticApplicationRole READ = SemanticApplicationRole.builder()
            .system(SYSTEM)
            .resource("line")
            .operation("read")
            .build();

    @Test
    void canCreate_onlyForThePartnerTheRoleIsHeldFor() {
        LineAuthorization authorization = new LineAuthorization(ServletSemanticAuthorizationMock.builder()
                .systemName(SYSTEM)
                .businessPartnerRole(ACME, Set.of(CREATE))
                .build());

        assertThat(authorization.canCreate(ACME)).isTrue();
        assertThat(authorization.canCreate(OTHER_CORP)).isFalse();
    }

    @Test
    void canDelete_onlyForUserIndependentRole_notABusinessPartnerRole() {
        LineAuthorization businessPartnerOnly = new LineAuthorization(ServletSemanticAuthorizationMock.builder()
                .systemName(SYSTEM)
                .businessPartnerRole(ACME, Set.of(DELETE))
                .build());
        LineAuthorization regularUser = new LineAuthorization(ServletSemanticAuthorizationMock.builder()
                .systemName(SYSTEM)
                .userRole(DELETE)
                .build());

        assertThat(businessPartnerOnly.canDelete()).isFalse();
        assertThat(regularUser.canDelete()).isTrue();
    }

    @Test
    void isOwner_trueWhenAffiliatedWithTheLinesBusinessPartner_regardlessOfWhichRole() {
        // holds *some* role for "acme" (delete, not create) -- ownership isn't role-specific
        LineAuthorization authorization = new LineAuthorization(ServletSemanticAuthorizationMock.builder()
                .systemName(SYSTEM)
                .businessPartnerRole(ACME, Set.of(DELETE))
                .build());
        Line ownedByAcme = LineFixture.newBuilder().businessPartnerId(ACME).build();
        Line ownedByOtherCorp =
                LineFixture.newBuilder().businessPartnerId(OTHER_CORP).build();

        assertThat(authorization.isOwner(ownedByAcme)).isTrue();
        assertThat(authorization.isOwner(ownedByOtherCorp)).isFalse();
    }

    /**
     * {@link LineAuthorization#can(Class, Line)} mirrors {@link Line#can(Class)} on the
     * authorization side, and likewise throws {@link IllegalStateException} for any {@link
     * LineCommand} subtype it doesn't recognize -- this test iterates every permitted subclass so
     * a newly added command type without a matching branch fails loudly in CI.
     */
    @Nested
    class Can {

        @ParameterizedTest
        @MethodSource("li.selman.optimisticlocking.line.LineAuthorizationTest#commandTypes")
        void commandTypeSupported(Class<? extends LineCommand> commandType) {
            LineAuthorization authorization = new LineAuthorization(
                    ServletSemanticAuthorizationMock.builder().systemName(SYSTEM).build());
            Line line = LineFixture.newBuilder().build();

            assertThatCode(() -> authorization.can(commandType, line)).doesNotThrowAnyException();
        }

        @Test
        void delete_onlyForUserIndependentRole() {
            LineAuthorization businessPartnerOnly = new LineAuthorization(ServletSemanticAuthorizationMock.builder()
                    .systemName(SYSTEM)
                    .businessPartnerRole(ACME, Set.of(DELETE))
                    .build());
            LineAuthorization regularUser = new LineAuthorization(ServletSemanticAuthorizationMock.builder()
                    .systemName(SYSTEM)
                    .userRole(DELETE)
                    .build());
            Line line = LineFixture.newBuilder().businessPartnerId(ACME).build();

            assertThat(businessPartnerOnly.can(LineCommand.DeleteLine.class, line)).isFalse();
            assertThat(regularUser.can(LineCommand.DeleteLine.class, line)).isTrue();
        }

        @Test
        void move_onlyForTheOwningBusinessPartner() {
            LineAuthorization authorization = new LineAuthorization(ServletSemanticAuthorizationMock.builder()
                    .systemName(SYSTEM)
                    .businessPartnerRole(ACME, Set.of(CREATE))
                    .build());
            Line ownedByAcme = LineFixture.newBuilder().businessPartnerId(ACME).build();
            Line ownedByOtherCorp =
                    LineFixture.newBuilder().businessPartnerId(OTHER_CORP).build();

            assertThat(authorization.can(LineCommand.MoveLeft.class, ownedByAcme)).isTrue();
            assertThat(authorization.can(LineCommand.MoveRight.class, ownedByAcme)).isTrue();
            assertThat(authorization.can(LineCommand.MoveLeft.class, ownedByOtherCorp))
                    .isFalse();
            assertThat(authorization.can(LineCommand.MoveRight.class, ownedByOtherCorp))
                    .isFalse();
        }

        /**
         * Move mutates the line, so it needs {@code line_#create}, not just any affiliation --
         * unlike {@link LineAuthorization#isOwner}, which deliberately treats a read-only role as
         * enough to view a line. A caller holding only {@code line_#read} for the owning partner
         * must not be able to move it.
         */
        @Test
        void move_readOnlyRoleForTheOwningPartner_notEnough() {
            LineAuthorization authorization = new LineAuthorization(ServletSemanticAuthorizationMock.builder()
                    .systemName(SYSTEM)
                    .businessPartnerRole(ACME, Set.of(READ))
                    .build());
            Line ownedByAcme = LineFixture.newBuilder().businessPartnerId(ACME).build();

            assertThat(authorization.isOwner(ownedByAcme)).isTrue(); // affiliated -> can view
            assertThat(authorization.can(LineCommand.MoveLeft.class, ownedByAcme))
                    .isFalse(); // but not enough to edit
            assertThat(authorization.can(LineCommand.MoveRight.class, ownedByAcme))
                    .isFalse();
        }
    }

    static Stream<Class<? extends LineCommand>> commandTypes() {
        return Arrays.stream(LineCommand.class.getPermittedSubclasses()).map(c -> c.asSubclass(LineCommand.class));
    }
}
