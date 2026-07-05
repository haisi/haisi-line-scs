package li.selman.optimisticlocking.shared.web;

import ch.admin.bit.jeap.security.resource.token.AuthoritiesResolver;
import ch.admin.bit.jeap.security.resource.token.JeapAuthenticationConverter;
import ch.admin.bit.jeap.security.resource.validation.JeapJwtDecoderFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class ApiSecurityConfig {

    /**
     * The jeap-spring-boot-security-starter's own default filter chain is registered at
     * {@code Ordered.LOWEST_PRECEDENCE} specifically so applications can override it. This app is
     * a pure Bearer-token JSON API with no browser session/cookie for an attacker to ride along
     * on, so CSRF protection -- which exists to guard ambient cookie credentials -- does not
     * apply here; everything else (JWT decoding, role/context extraction) is reused as-is.
     *
     * <p>Scoped to {@code /lines/**} rather than any-request: Spring Security refuses to register
     * two filter chains that both match any request (regardless of {@code @Order}), so the
     * starter's own catch-all chain is left in place for anything outside this API.
     */
    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE - 1)
    SecurityFilterChain apiSecurityFilterChain(
            HttpSecurity http, JeapJwtDecoderFactory jwtDecoderFactory, AuthoritiesResolver authoritiesResolver) {
        http.securityMatcher("/lines/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().fullyAuthenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.decoder(jwtDecoderFactory.createJwtDecoder())
                        .jwtAuthenticationConverter(new JeapAuthenticationConverter(authoritiesResolver))));
        return http.build();
    }
}
