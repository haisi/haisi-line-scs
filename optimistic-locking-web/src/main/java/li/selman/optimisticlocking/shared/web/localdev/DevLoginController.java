package li.selman.optimisticlocking.shared.web.localdev;

import ch.admin.bit.jeap.security.resource.token.JeapAuthenticationContext;
import ch.admin.bit.jeap.security.test.jws.JwsBuilderFactory;
import java.util.Map;
import li.selman.optimisticlocking.line.LineAuthorization;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Mints demo bearer tokens for the Angular app's identity switcher -- see {@link LocalDevAuthConfig}
 * for why this exists at all. Both demo identities hold {@code line_#read} for the fixed business
 * partner {@code acme} (matching {@code LineFixture}/{@code LocalDataSeeder}); only "administrator"
 * additionally holds {@code line_#delete} (a user role, making {@code LineModelAssembler}
 * add a {@code delete} HATEOAS link) and {@code line_#create} for {@code acme} (a business-partner
 * role, making it add {@code moveLeft}/{@code moveRight} links on each embedded point, per
 * {@link LineAuthorization#canEdit}) -- the roles that make the delete and edit actions appear at
 * all on a fetched {@code Line}.
 */
@RestController
@Profile("local")
public class DevLoginController {

    private static final String BUSINESS_PARTNER_ID = "acme";
    private static final String ADMINISTRATOR = "administrator";

    private final JwsBuilderFactory jwsBuilderFactory;

    DevLoginController(JwsBuilderFactory jwsBuilderFactory) {
        this.jwsBuilderFactory = jwsBuilderFactory;
    }

    @PostMapping("/dev/login")
    Map<String, String> login(@RequestParam String identity) {
        var builder = jwsBuilderFactory
                .createValidForFixedLongPeriodBuilder("demo-" + identity, JeapAuthenticationContext.USER)
                .withBusinessPartnerRoles(BUSINESS_PARTNER_ID, LineAuthorization.READ_ROLE);
        if (ADMINISTRATOR.equals(identity)) {
            builder.withBusinessPartnerRoles(BUSINESS_PARTNER_ID, LineAuthorization.CREATE_ROLE)
                    .withUserRoles(LineAuthorization.DELETE_ROLE);
        }
        return Map.of("identity", identity, "token", builder.build().serialize());
    }
}
