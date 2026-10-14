package io.saas.forge.tenantaccess.application.membership;

import io.saas.forge.tenantaccess.application.tenant.SessionRevocationGateway.Result;
import java.util.UUID;

/** 只撤销目标 Membership；同一 Identity 的其他 Tenant 会话不在此操作范围内。 */
public interface MembershipRevocationGateway {
    Result revoke(UUID requestId, UUID tenantId, UUID membershipId);
    void recover(UUID requestId, UUID tenantId, UUID membershipId);
}
