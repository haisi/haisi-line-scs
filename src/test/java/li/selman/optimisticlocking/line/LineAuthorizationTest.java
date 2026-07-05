package li.selman.optimisticlocking.line;

import static org.assertj.core.api.Assertions.assertThat;

import ch.admin.bit.jeap.security.resource.semanticAuthentication.SemanticApplicationRole;
import ch.admin.bit.jeap.security.test.resource.ServletSemanticAuthorizationMock;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LineAuthorizationTest {

    private static final String SYSTEM = "optimistic-locking";
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
}
