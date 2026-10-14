package io.saas.forge.tenantaccess.application.membership;

import java.util.UUID;

/** 启用独立占用一个名额；补偿只释放该次占用，不复用原禁用的 Quota Operation。 */
public interface MembershipEnableQuotaGateway {
    void consume(UUID tenantId, UUID operationId);
    void release(UUID tenantId, UUID operationId);
}
