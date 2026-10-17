package io.saas.forge.tenantaccess.application.membership;

import java.util.UUID;

public interface MembershipQuotaGateway {
    void release(UUID tenantId, UUID operationId);
}
