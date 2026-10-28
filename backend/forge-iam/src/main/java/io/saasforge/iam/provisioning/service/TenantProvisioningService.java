package io.saasforge.iam.provisioning.service;

import io.saasforge.iam.identity.model.ContactIdentity;
import io.saasforge.iam.identity.service.IdentityService;
import io.saasforge.iam.invitation.service.InvitationService;
import io.saasforge.iam.membership.model.MembershipStatus;
import io.saasforge.iam.membership.service.MembershipService;
import io.saasforge.iam.role.service.RoleService;
import io.saasforge.iam.tenant.service.TenantService;
import io.saasforge.sdk.provisioning.TenantCreated;
import io.saasforge.sdk.provisioning.TenantInitializer;
import org.springframework.stereotype.Service;

/**
 * iam 的租户初始化：登记租户、预置 Owner 与 Tenant Admin、把初始管理员加为 invited 的 Owner 并发出邀请。
 *
 * <p>每一步都可重复执行：重试时已建立的数据保持不变；Membership 已被接受时不再发邀请。
 */
@Service
class TenantProvisioningService implements TenantInitializer {
    private final TenantService tenants;
    private final RoleService roles;
    private final IdentityService identities;
    private final MembershipService memberships;
    private final InvitationService invitations;

    TenantProvisioningService(
            TenantService tenants,
            RoleService roles,
            IdentityService identities,
            MembershipService memberships,
            InvitationService invitations) {
        this.tenants = tenants;
        this.roles = roles;
        this.identities = identities;
        this.memberships = memberships;
        this.invitations = invitations;
    }

    @Override
    public void initialize(TenantCreated tenant) {
        tenants.register(tenant.tenantId(), tenant.name());
        roles.provisionBuiltinRoles(tenant.tenantId());
        ContactIdentity admin = identities.findOrCreatePending(tenant.adminContact());
        MembershipStatus membership = memberships.invite(tenant.tenantId(), admin.identityId());
        roles.grantOwner(tenant.tenantId(), membership.membershipId());
        if (membership.invited()) {
            invitations.invite(tenant.adminContact(), tenant.name(), membership, admin.pending());
        }
    }
}
