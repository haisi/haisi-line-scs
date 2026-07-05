package li.selman.optimisticlocking.line;

import ch.admin.bit.jeap.security.resource.semanticAuthentication.ServletSemanticAuthorization;
import java.util.Set;
import org.jmolecules.architecture.onion.simplified.ApplicationRing;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * Authorization rules for the {@code line} resource, built on jEAP's semantic roles
 * (system_%tenant_@resource_#operation). {@code create} is business-partner-scoped (a partner
 * needs {@code line_#create} for the specific partner it's creating on behalf of), while
 * {@code delete} is user-independent (an internal/regular user needs {@code line_#delete} for
 * any partner). View/edit is not gated by a role at all -- it's an ownership check: only a
 * caller currently affiliated with the line's creating business partner (holding any role for
 * that partner) may access it.
 */
@ApplicationRing
@Component
public class LineAuthorization {

    private static final String RESOURCE = "line";
    private static final String CREATE = "create";
    private static final String DELETE = "delete";

    private final ServletSemanticAuthorization authorization;

    LineAuthorization(ServletSemanticAuthorization authorization) {
        this.authorization = authorization;
    }

    public boolean canCreate(String businessPartnerId) {
        return authorization.hasRoleForPartner(RESOURCE, CREATE, businessPartnerId);
    }

    public void requireCanCreate(String businessPartnerId) {
        if (!canCreate(businessPartnerId)) {
            throw new AccessDeniedException(
                    "Missing role '%s#%s' for business partner %s".formatted(RESOURCE, CREATE, businessPartnerId));
        }
    }

    public boolean canDelete() {
        return authorization.hasRoleForAllPartners(RESOURCE, DELETE);
    }

    public void requireCanDelete() {
        if (!canDelete()) {
            throw new AccessDeniedException("Missing role '%s#%s'".formatted(RESOURCE, DELETE));
        }
    }

    public boolean isOwner(Line line) {
        return currentBusinessPartnerIds().contains(line.getBusinessPartnerId());
    }

    public void requireOwnership(Line line) {
        if (!isOwner(line)) {
            throw new AccessDeniedException("Not affiliated with business partner " + line.getBusinessPartnerId());
        }
    }

    public Set<String> currentBusinessPartnerIds() {
        return authorization.getAuthenticationToken().getBusinessPartnerRoles().keySet();
    }
}
