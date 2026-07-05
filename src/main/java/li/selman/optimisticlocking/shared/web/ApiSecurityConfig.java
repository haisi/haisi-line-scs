package li.selman.optimisticlocking.shared.web;

import ch.admin.bit.jeap.security.resource.token.AuthoritiesResolver;
import ch.admin.bit.jeap.security.resource.token.JeapAuthenticationConverter;
import ch.admin.bit.jeap.security.resource.validation.JeapJwtDecoderFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

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
     *
     * <p>{@link BusinessPartnerFilter} is added after {@link AuthorizationFilter} so it only ever
     * runs for a request that has already cleared {@code fullyAuthenticated()} -- its business
     * partner check assumes a real {@code JeapAuthenticationToken} is present.
     */
    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE - 1)
    SecurityFilterChain apiSecurityFilterChain(
            HttpSecurity http,
            JeapJwtDecoderFactory jwtDecoderFactory,
            AuthoritiesResolver authoritiesResolver,
            BusinessPartnerFilter businessPartnerFilter) {
        http.securityMatcher("/lines/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().fullyAuthenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.decoder(jwtDecoderFactory.createJwtDecoder())
                        .jwtAuthenticationConverter(new JeapAuthenticationConverter(authoritiesResolver))))
                .addFilterAfter(businessPartnerFilter, AuthorizationFilter.class);
        return http.build();
    }

    /**
     * {@link BusinessPartnerFilter} is a {@code @Component} solely so Spring can inject its {@code
     * ServletSemanticAuthorization} dependency; without this, Spring Boot would additionally
     * auto-register it as a servlet-container-wide filter on {@code /*}, running it a second time
     * outside the security chain built above.
     */
    @Bean
    FilterRegistrationBean<BusinessPartnerFilter> disableAutoRegistration(BusinessPartnerFilter filter) {
        FilterRegistrationBean<BusinessPartnerFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
