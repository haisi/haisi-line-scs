package li.selman.optimisticlocking.shared.web;

import ch.admin.bit.jeap.security.resource.semanticAuthentication.ServletSemanticAuthorization;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects any request that names a business partner via {@value #PARTNER_ID_HEADER} the caller
 * isn't actually affiliated with -- callers act as a single business partner per request, and this
 * header is how they declare which one, so an unaffiliated id here is an authorization failure, not
 * a 404/400. Wired into {@link ApiSecurityConfig} after Spring Security's {@code AuthorizationFilter},
 * so {@code getAuthenticationToken()} is only ever reached for an already-fully-authenticated request.
 */
@Component
public class BusinessPartnerFilter extends OncePerRequestFilter {

    public static final String PARTNER_ID_HEADER = "X-Partner-Id";

    private final ServletSemanticAuthorization authorization;

    public BusinessPartnerFilter(ServletSemanticAuthorization authorization) {
        this.authorization = authorization;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String partnerId = request.getHeader(PARTNER_ID_HEADER);
        if (partnerId != null
                && !authorization
                        .getAuthenticationToken()
                        .getBusinessPartnerRoles()
                        .containsKey(partnerId)) {
            throw new AccessDeniedException("Not affiliated with business partner " + partnerId);
        }
        filterChain.doFilter(request, response);
    }
}
