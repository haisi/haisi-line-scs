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
     * partner check assumes a real {@code JeapAuthenticationToken} is present. {@link
     * IdempotencyFilter} is added after that again, so it only ever reserves/replays for a request
     * that also cleared partner-affiliation -- nothing is ever cached for a request either of the
     * two filters ahead of it would have rejected anyway.
     */
    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE - 1)
    SecurityFilterChain apiSecurityFilterChain(
            HttpSecurity http,
            JeapJwtDecoderFactory jwtDecoderFactory,
            AuthoritiesResolver authoritiesResolver,
            BusinessPartnerFilter businessPartnerFilter,
            IdempotencyFilter idempotencyFilter) {
        http.securityMatcher("/lines/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().fullyAuthenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.decoder(jwtDecoderFactory.createJwtDecoder())
                        .jwtAuthenticationConverter(new JeapAuthenticationConverter(authoritiesResolver))))
                .addFilterAfter(businessPartnerFilter, AuthorizationFilter.class)
                .addFilterAfter(idempotencyFilter, BusinessPartnerFilter.class);
        return http.build();
    }

    /**
     * The generated API guide under {@code /docs/**} (see the asciidoctor-maven-plugin and
     * maven-resources-plugin executions in {@code pom.xml}) is static, read-only, and contains
     * nothing more sensitive than the {@code /lines/**} chain's own request/response shapes -- so,
     * unlike the API itself, it's left open for anyone following along with the talk to read
     * without first minting a token. Ordered ahead of the starter's own catch-all chain, the same
     * way {@link #apiSecurityFilterChain} is.
     */
    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE - 2)
    SecurityFilterChain docsSecurityFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/docs/**")
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());
        return http.build();
    }

    /**
     * The Angular SPA embedded from {@code optimistic-locking-ui} (its {@code index.html} plus
     * hashed JS/CSS bundles, served by Spring Boot's default {@code classpath:/static/**}
     * handling) has to be loadable by a browser with no bearer token yet -- that's the only way
     * the app can ever get one, via its own in-page identity switcher calling {@code /dev/login}.
     * Without this, the request would fall through to the starter's own catch-all chain and 401
     * before a single byte of the SPA loads. Scoped to the shell's own static asset shapes rather
     * than {@code /**} for the same reason {@link #apiSecurityFilterChain} is scoped to {@code
     * /lines/**}: Spring Security rejects a second any-request chain. Everything the SPA actually
     * fetches at runtime after that -- {@code /lines/**} -- still goes through the real, opaque
     * bearer-token chain above.
     */
    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE - 4)
    SecurityFilterChain frontendAssetsSecurityFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/", "/index.html", "/favicon.ico", "/assets/**", "/*.js", "/*.css", "/*.txt", "/*.json")
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());
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

    /** Same reasoning as {@link #disableAutoRegistration} above, for {@link IdempotencyFilter}. */
    @Bean
    FilterRegistrationBean<IdempotencyFilter> disableIdempotencyFilterAutoRegistration(IdempotencyFilter filter) {
        FilterRegistrationBean<IdempotencyFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
