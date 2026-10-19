package io.saas.forge.tenantaccess.application.tenant;

import io.saas.forge.tenantaccess.domain.tenant.Tenant;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface TenantDetailsRepository {
    void setTarget(UUID tenantId);

    boolean claim(UUID tenantId, UUID actorIdentityId, UUID idempotencyKey, String fingerprint);

    Optional<Update> find(UUID tenantId, UUID actorIdentityId, UUID idempotencyKey);

    Tenant lockTenant(UUID tenantId);

    boolean lifecycleChangeInProgress(UUID tenantId);

    void save(Tenant tenant);

    void complete(UUID tenantId, UUID actorIdentityId, UUID idempotencyKey, Tenant result, Instant completedAt);

    record Update(String fingerprint, Tenant result) {}
}
