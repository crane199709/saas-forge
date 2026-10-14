package io.saas.forge.tenantaccess.application.tenant;

import io.saas.forge.tenantaccess.domain.outbox.OutboxEvent;
import io.saas.forge.tenantaccess.domain.tenant.Tenant;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface TenantLifecycleRepository {
    TenantLifecycleSnapshot readLifecycle(UUID tenantId);

    TenantLifecycleClaim prepare(
            UUID actorIdentityId, UUID idempotencyKey, UUID tenantId, TenantLifecycleAction action,
            String fingerprint, UUID workflowId, UUID revocationRequestId, UUID releaseRequestId, Instant at);

    TenantLifecycleClaim prepareRecovery(
            UUID actorIdentityId, UUID idempotencyKey, UUID tenantId, String fingerprint, Instant at);

    TenantLifecycleClaim prepareClosureRecovery(
            UUID actorIdentityId, UUID idempotencyKey, UUID tenantId, String fingerprint, Instant at);

    Optional<TenantLifecycleWorkflow> find(UUID workflowId);

    Tenant loadTenant(UUID tenantId);

    Optional<TenantLifecycleWorkflow> claim(
            UUID workflowId, String claimant, Instant at, Instant leaseUntil, int maximumAttempts);

    Optional<TenantLifecycleWorkflow> claimNext(
            String claimant, Instant at, Instant leaseUntil, int maximumAttempts);

    void confirmFence(TenantLifecycleWorkflow workflow);

    void markRevocationAttempt(TenantLifecycleWorkflow workflow);

    void confirmIamRecovery(TenantLifecycleWorkflow workflow, Instant at);

    void schedulePending(TenantLifecycleWorkflow workflow, Instant retryAt);

    void scheduleFailure(
            TenantLifecycleWorkflow workflow, Instant at, Instant retryAt, String failure, int maximumAttempts,
            boolean fenceMayHaveBeenEstablished);

    TenantLifecycleResult complete(
            TenantLifecycleWorkflow workflow, Tenant tenant, long revokedFamilyCount, long revokedJtiCount,
            Instant at, OutboxEvent suspensionEvent);
}
