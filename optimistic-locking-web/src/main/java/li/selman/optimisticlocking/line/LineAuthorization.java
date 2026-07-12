package li.selman.optimisticlocking.line;

import ch.admin.bit.jeap.security.resource.semanticAuthentication.SemanticApplicationRole;
import ch.admin.bit.jeap.security.resource.semanticAuthentication.ServletSemanticAuthorization;
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

    public void assertCanCreate(String businessPartnerId) {
        if (!canCreate(businessPartnerId)) {
            throw new AccessDeniedException(
                    "Missing role '%s' for business partner %s".formatted(CREATE_ROLE, businessPartnerId));
        }
    }

    /**
     * Regardless of who created the line. A line can only be deleted by user-role line_#delete
     */
    public boolean canDelete() {
        return authorization.hasRoleForAllPartners(DELETE_ROLE);
    }

    public void assertCanDelete() {
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

    public void assertOwnership(Line line) {
        if (!isOwner(line)) {
            throw new AccessDeniedException("Not affiliated with business partner " + line.getBusinessPartnerId());
        }
    }

    /**
     * A move mutates the line, so -- unlike {@link #isOwner}, which deliberately accepts *any*
     * role for view access -- it needs the same {@code line_#create} role as creating one,
     * checked against the line's own stored {@code businessPartnerId} rather than the
     * caller-supplied {@code X-Partner-Id} header. This mirrors the defense-in-depth
     * {@link #assertCanCreate} already gives {@code create}: naming a partner you're genuinely
     * affiliated with (e.g. holding only {@code line_#read} there) must not let you move a
     * *different* partner's line by id.
     */
    public boolean canEdit(Line line) {
        return canCreate(line.getBusinessPartnerId());
    }

    public void assertCanEdit(Line line) {
        if (!canEdit(line)) {
            throw new AccessDeniedException(
                    "Missing role '%s' for business partner %s".formatted(CREATE_ROLE, line.getBusinessPartnerId()));
        }
    }

    /**
     * Determines whether the caller is permitted to issue this <b>type of command</b> against
     * {@code line}, mirroring {@link Line#can(Class)} on the authorization side. {@code create}
     * doesn't apply here (the line already exists), so it defaults to allowed; {@code delete} is
     * user-independent (see {@link #canDelete()}); {@code move} requires edit rights, same as
     * {@link LineService#move} enforces via {@link #assertCanEdit(Line)}.
     */
    public boolean can(Class<? extends LineCommand> commandType, Line line) {
        if (commandType == LineCommand.CreateLine.class) {
            return true;
        } else if (commandType == LineCommand.DeleteLine.class) {
            return canDelete();
        } else if (commandType == LineCommand.MoveLeft.class || commandType == LineCommand.MoveRight.class) {
            return canEdit(line);
        }
        throw new IllegalStateException("No 'can' check for " + commandType);
    }
}
