package io.saas.forge.tenantaccess.application.invitation;

import java.util.UUID;

public interface InvitationQuotaGateway {
    void consume(UUID tenantId, UUID operationId);
    void release(UUID tenantId, UUID operationId);
}
