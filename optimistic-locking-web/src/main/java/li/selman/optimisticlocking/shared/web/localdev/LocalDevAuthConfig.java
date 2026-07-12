package li.selman.optimisticlocking.shared.web.localdev;

import ch.admin.bit.jeap.security.test.resource.configuration.JeapOAuth2IntegrationTestResourceConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stands in for a real Keycloak realm during local development: {@code application.properties}'
 * OAuth2 issuer is a documented placeholder, so nothing can otherwise obtain a bearer token that
 * this app's resource server will accept. Importing {@link JeapOAuth2IntegrationTestResourceConfiguration}
 * -- ordinarily only pulled into {@code @SpringBootTest}s like {@code LineControllerITTest} --
 * registers a real {@code @RestController} serving a mock JWKS endpoint plus a {@code
 * JwsBuilderFactory} bean, both usable at actual runtime, not just in tests. {@link DevLoginController}
 * uses that factory to mint tokens for two fixed demo identities.
 *
 * <p>Active only under the {@code local} Spring profile ({@code -Dspring-boot.run.profiles=local}),
 * and matched to the same fixed {@code server.port=8080} {@code application-local.properties} pins
 * -- like {@code LineControllerITTest}, the resource server's {@code JwtDecoder} is built from the
 * {@code jwk-set-uri} property well before the embedded web server would publish an actual bound
 * port, so a fixed port is required, not a convenience.
 */
@Configuration
@Profile("local")
@Import(JeapOAuth2IntegrationTestResourceConfiguration.class)
public class LocalDevAuthConfig {

    /**
     * {@code /dev/login} must be reachable without a bearer token -- that's the only way to get
     * one in the first place -- so it needs its own permitAll chain, the same way {@code
     * ApiSecurityConfig.docsSecurityFilterChain} opens up {@code /docs/**}.
     */
    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE - 3)
    SecurityFilterChain devLoginSecurityFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/dev/**")
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());
        return http.build();
    }
}
