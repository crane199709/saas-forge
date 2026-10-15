package io.saas.forge.tenantaccess.application.membership;

import java.util.UUID;

/** 仅解除完成禁用时建立的 Membership Fence 代际，保留 IAM 撤销事实。 */
public interface MembershipEnableFenceGateway {
    void release(UUID releaseId, UUID revocationId, UUID tenantId, UUID membershipId);
}
