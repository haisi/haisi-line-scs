package li.selman.optimisticlocking.line;

import ch.admin.bit.jeap.security.resource.semanticAuthentication.SemanticApplicationRole;
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

    private static final String SYSTEM = "wvs";
    private static final String RESOURCE = "line";

    public static final SemanticApplicationRole READ_ROLE = SemanticApplicationRole.builder()
            .system(SYSTEM)
            .resource(RESOURCE)
            .operation("read")
            .build();
    public static final SemanticApplicationRole DELETE_ROLE = SemanticApplicationRole.builder()
            .system(SYSTEM)
            .resource(RESOURCE)
            .operation("delete")
            .build();
    public static final SemanticApplicationRole CREATE_ROLE = SemanticApplicationRole.builder()
            .system(SYSTEM)
            .resource(RESOURCE)
            .operation("create")
            .build();

    private final ServletSemanticAuthorization authorization;

    LineAuthorization(ServletSemanticAuthorization authorization) {
        this.authorization = authorization;
    }

    public boolean canCreate(String businessPartnerId) {
        return authorization.hasRoleForPartner(CREATE_ROLE, businessPartnerId);
    }

    public void requireCanCreate(String businessPartnerId) {
        if (!canCreate(businessPartnerId)) {
            throw new AccessDeniedException(
                    "Missing role '%s' for business partner %s".formatted(CREATE_ROLE, businessPartnerId));
        }
    }

    public boolean canDelete() {
        return authorization.hasRoleForAllPartners(DELETE_ROLE);
    }

    public void requireCanDelete() {
        if (!canDelete()) {
            throw new AccessDeniedException("Missing role '%s'".formatted(DELETE_ROLE));
        }
    }

    /**
     * True if the caller holds any of the {@code line} roles for this line's owning business
     * partner -- deliberately not restricted to {@code READ_ROLE}, since holding e.g. only
     * {@code create} for a partner should still count as "affiliated" with its lines. Being
     * affiliated with the partner for some *other*, unrelated system/resource does not count:
     * {@link ch.admin.bit.jeap.security.resource.semanticAuthentication.SemanticRoleRepository}
     * already scopes every one of these checks down to the {@code line} resource's system, so a
     * partner where the caller only holds a role from a different system is correctly treated as
     * not owned. A caller holding one of these roles for *all* partners (the BAZG "admin" case)
     * also passes here for free, since {@code hasRoleForPartner} itself falls back to {@code
     * hasRoleForAllPartners}.
     */
    public boolean isOwner(Line line) {
        String businessPartnerId = line.getBusinessPartnerId();
        return authorization.hasRoleForPartner(READ_ROLE, businessPartnerId)
                || authorization.hasRoleForPartner(CREATE_ROLE, businessPartnerId)
                || authorization.hasRoleForPartner(DELETE_ROLE, businessPartnerId);
    }

    public void requireOwnership(Line line) {
        if (!isOwner(line)) {
            throw new AccessDeniedException("Not affiliated with business partner " + line.getBusinessPartnerId());
        }
    }

    /** True for a caller holding {@code READ_ROLE} user-independently, e.g. the BAZG "admin". */
    public boolean canReadAll() {
        return authorization.hasRoleForAllPartners(READ_ROLE);
    }

    /** Every business partner the caller holds {@code READ_ROLE} for -- meaningless if {@link #canReadAll()}. */
    public Set<String> readableBusinessPartnerIds() {
        return Set.copyOf(authorization.getPartnersForRole(READ_ROLE));
    }
}
