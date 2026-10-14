package io.saas.forge.tenantaccess.infrastructure.persistence;

import io.saas.forge.tenantaccess.application.tenant.IdempotencyKeyReusedException;
import io.saas.forge.tenantaccess.application.tenant.TenantLifecycleAction;
import io.saas.forge.tenantaccess.application.tenant.TenantLifecycleClaim;
import io.saas.forge.tenantaccess.application.tenant.TenantLifecycleException;
import io.saas.forge.tenantaccess.application.tenant.TenantLifecycleRepository;
import io.saas.forge.tenantaccess.application.tenant.TenantLifecycleResult;
import io.saas.forge.tenantaccess.application.tenant.TenantLifecycleStatus;
import io.saas.forge.tenantaccess.application.tenant.TenantLifecycleWorkflow;
import io.saas.forge.tenantaccess.domain.outbox.OutboxEvent;
import io.saas.forge.tenantaccess.domain.tenant.Tenant;
import io.saas.forge.tenantaccess.domain.tenant.TenantStateTransitionNotAllowedException;
import io.saas.forge.tenantaccess.domain.tenant.TenantStatus;
import io.saas.forge.tenantaccess.infrastructure.persistence.mapper.TenantCreationMapper;
import io.saas.forge.tenantaccess.infrastructure.persistence.mapper.TenantLifecycleMapper;
import io.saas.forge.tenantaccess.infrastructure.persistence.record.TenantAccessOutboxRow;
import io.saas.forge.tenantaccess.infrastructure.persistence.record.TenantLifecycleRow;
import io.saas.forge.tenantaccess.infrastructure.persistence.record.TenantRow;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Repository
public class MyBatisTenantLifecycleRepository implements TenantLifecycleRepository {
    private final TenantLifecycleMapper mapper;
    private final TenantCreationMapper creationMapper;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public io.saas.forge.tenantaccess.application.tenant.TenantLifecycleSnapshot readLifecycle(UUID tenantId) {
        setTarget(tenantId);
        TenantRow tenant = mapper.lockTenant(tenantId);
        if (tenant == null) throw failure("TENANT_NOT_FOUND", "Tenant 不存在");
        return new io.saas.forge.tenantaccess.application.tenant.TenantLifecycleSnapshot(
                toDomain(tenant), Optional.ofNullable(mapper.findLatest(tenantId)).map(this::toDomain));
    }

    public MyBatisTenantLifecycleRepository(
            TenantLifecycleMapper mapper, TenantCreationMapper creationMapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.creationMapper = creationMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public TenantLifecycleClaim prepare(
            UUID actorIdentityId, UUID idempotencyKey, UUID tenantId, TenantLifecycleAction action,
            String fingerprint, UUID workflowId, UUID revocationRequestId, UUID releaseRequestId, Instant at) {
        setTarget(tenantId);
        TenantLifecycleRow existing = mapper.findByExternalKey(actorIdentityId, idempotencyKey);
        if (existing != null) {
            if (!existing.getRequestFingerprint().equals(fingerprint)) throw new IdempotencyKeyReusedException();
            return new TenantLifecycleClaim(TenantLifecycleClaim.Status.REPLAY, toDomain(existing));
        }
        TenantRow tenant = mapper.lockTenant(tenantId);
        if (tenant == null) throw failure("TENANT_NOT_FOUND", "Tenant 不存在");
        if (mapper.findActive(tenantId) != null) {
            throw failure("TENANT_LIFECYCLE_CHANGE_IN_PROGRESS", "Tenant 已有生命周期变更正在进行");
        }
        if (action == TenantLifecycleAction.CLOSE) {
            if (!"ACTIVE".equals(tenant.status()) && !"SUSPENDED".equals(tenant.status())) {
                throw new TenantStateTransitionNotAllowedException();
            }
        } else {
            String expected = action == TenantLifecycleAction.SUSPEND ? "ACTIVE" : "SUSPENDED";
            if (!expected.equals(tenant.status())) throw new TenantStateTransitionNotAllowedException();
        }
        UUID boundRevocationRequestId = revocationRequestId;
        if (action == TenantLifecycleAction.RESUME) {
            boundRevocationRequestId = mapper.findLatestSuspensionRequest(tenantId);
            if (boundRevocationRequestId == null) {
                throw failure("TENANT_STATE_TRANSITION_NOT_ALLOWED", "Tenant 没有可释放的 Suspension Fence generation");
            }
        } else if (action == TenantLifecycleAction.CLOSE && "SUSPENDED".equals(tenant.status())) {
            // 已冻结的 Tenant 保持原 Fence generation；IAM 拒绝同一目标上的第二个活跃 Fence。
            boundRevocationRequestId = mapper.findLatestSuspensionRequest(tenantId);
            if (boundRevocationRequestId == null) {
                throw failure("TENANT_STATE_TRANSITION_NOT_ALLOWED", "Tenant 没有可沿用的 Suspension Fence generation");
            }
        }
        TenantLifecycleRow row = new TenantLifecycleRow();
        row.setWorkflowId(workflowId);
        row.setTenantId(tenantId);
        row.setActorIdentityId(actorIdentityId);
        row.setIdempotencyKey(idempotencyKey);
        row.setRequestFingerprint(fingerprint);
        row.setLifecycleAction(action.name());
        row.setRevocationRequestId(boundRevocationRequestId);
        row.setReleaseRequestId(releaseRequestId);
        if (mapper.insert(row, TenantAccessTime.asOffsetDateTime(at)) != 1) {
            throw new IllegalStateException("Tenant Lifecycle Workflow 保存失败");
        }
        return new TenantLifecycleClaim(
                TenantLifecycleClaim.Status.CREATED, toDomain(mapper.find(workflowId)));
    }

    @Override
    @Transactional
    public TenantLifecycleClaim prepareRecovery(
            UUID actorIdentityId, UUID idempotencyKey, UUID tenantId, String fingerprint, Instant at) {
        return prepareRecovery(actorIdentityId, idempotencyKey, tenantId, fingerprint, at,
                TenantLifecycleAction.SUSPEND);
    }

    @Override
    @Transactional
    public TenantLifecycleClaim prepareClosureRecovery(
            UUID actorIdentityId, UUID idempotencyKey, UUID tenantId, String fingerprint, Instant at) {
        return prepareRecovery(actorIdentityId, idempotencyKey, tenantId, fingerprint, at,
                TenantLifecycleAction.CLOSE);
    }

    private TenantLifecycleClaim prepareRecovery(
            UUID actorIdentityId, UUID idempotencyKey, UUID tenantId, String fingerprint, Instant at,
            TenantLifecycleAction expectedAction) {
        setTarget(tenantId);
        var existing = mapper.findRecovery(actorIdentityId, idempotencyKey);
        if (existing != null) {
            if (!existing.requestFingerprint().equals(fingerprint)) throw new IdempotencyKeyReusedException();
            return new TenantLifecycleClaim(
                    TenantLifecycleClaim.Status.REPLAY, toDomain(mapper.find(existing.workflowId())));
        }
        if (mapper.lockTenant(tenantId) == null) throw failure("TENANT_NOT_FOUND", "Tenant 不存在");
        TenantLifecycleRow workflow = mapper.findActive(tenantId);
        if (workflow == null || !expectedAction.name().equals(workflow.getLifecycleAction())
                || !"RECOVERY_REQUIRED".equals(workflow.getWorkflowStatus())) {
            throw failure(expectedAction == TenantLifecycleAction.CLOSE
                    ? "TENANT_CLOSURE_RECOVERY_NOT_ALLOWED" : "TENANT_SUSPENSION_RECOVERY_NOT_ALLOWED",
                    "Tenant 没有需要显式恢复的生命周期 Workflow");
        }
        if (mapper.insertRecovery(actorIdentityId, idempotencyKey, tenantId, workflow.getWorkflowId(),
                fingerprint, TenantAccessTime.asOffsetDateTime(at)) != 1
                || mapper.startRecovery(workflow.getWorkflowId(), TenantAccessTime.asOffsetDateTime(at)) != 1) {
            throw new IllegalStateException("Tenant Suspension Recovery 保存失败");
        }
        return new TenantLifecycleClaim(
                TenantLifecycleClaim.Status.RECOVERY_STARTED, toDomain(mapper.find(workflow.getWorkflowId())));
    }

    @Override
    @Transactional
    public Optional<TenantLifecycleWorkflow> find(UUID workflowId) {
        if (mapper.setWorkflowTarget(workflowId) == null) return Optional.empty();
        TenantLifecycleRow row = mapper.find(workflowId);
        return Optional.of(toDomain(row));
    }

    @Override
    @Transactional
    public Tenant loadTenant(UUID tenantId) {
        setTarget(tenantId);
        TenantRow row = mapper.findTenant(tenantId);
        if (row == null) throw failure("TENANT_NOT_FOUND", "Tenant 不存在");
        return toDomain(row);
    }

    @Override
    @Transactional
    public Optional<TenantLifecycleWorkflow> claim(
            UUID workflowId, String claimant, Instant at, Instant leaseUntil, int maximumAttempts) {
        UUID claimed = mapper.claim(workflowId, claimant, TenantAccessTime.asOffsetDateTime(at),
                TenantAccessTime.asOffsetDateTime(leaseUntil), maximumAttempts);
        return claimed == null ? Optional.empty() : Optional.of(toDomain(mapper.find(claimed)));
    }

    @Override
    @Transactional
    public Optional<TenantLifecycleWorkflow> claimNext(
            String claimant, Instant at, Instant leaseUntil, int maximumAttempts) {
        UUID claimed = mapper.claimNext(claimant, TenantAccessTime.asOffsetDateTime(at),
                TenantAccessTime.asOffsetDateTime(leaseUntil), maximumAttempts);
        return claimed == null ? Optional.empty() : Optional.of(toDomain(mapper.find(claimed)));
    }

    @Override
    @Transactional
    public void markRevocationAttempt(TenantLifecycleWorkflow workflow) {
        setTarget(workflow.tenantId());
        if (mapper.markRevocationAttempt(workflow.workflowId(), workflow.fencingToken()) != 1) throw staleLease();
    }

    @Override
    @Transactional
    public void confirmFence(TenantLifecycleWorkflow workflow) {
        setTarget(workflow.tenantId());
        if (mapper.confirmFence(workflow.workflowId(), workflow.fencingToken()) != 1) throw staleLease();
    }

    @Override
    @Transactional
    public void confirmIamRecovery(TenantLifecycleWorkflow workflow, Instant at) {
        setTarget(workflow.tenantId());
        if (mapper.confirmIamRecovery(workflow.workflowId(), workflow.fencingToken(),
                TenantAccessTime.asOffsetDateTime(at)) != 1) throw staleLease();
    }

    @Override
    @Transactional
    public void schedulePending(TenantLifecycleWorkflow workflow, Instant retryAt) {
        setTarget(workflow.tenantId());
        if (mapper.schedulePending(workflow.workflowId(), workflow.fencingToken(),
                TenantAccessTime.asOffsetDateTime(retryAt)) != 1) throw staleLease();
    }

    @Override
    @Transactional
    public void scheduleFailure(
            TenantLifecycleWorkflow workflow, Instant at, Instant retryAt, String failure, int maximumAttempts,
            boolean fenceMayHaveBeenEstablished) {
        setTarget(workflow.tenantId());
        if (mapper.scheduleFailure(workflow.workflowId(), workflow.fencingToken(),
                TenantAccessTime.asOffsetDateTime(retryAt), failure, maximumAttempts,
                fenceMayHaveBeenEstablished, TenantAccessTime.asOffsetDateTime(at)) != 1) {
            throw staleLease();
        }
    }

    @Override
    @Transactional
    public TenantLifecycleResult complete(
            TenantLifecycleWorkflow workflow, Tenant tenant, long revokedFamilyCount, long revokedJtiCount,
            Instant at, OutboxEvent suspensionEvent) {
        setTarget(tenant.id());
        TenantRow locked = mapper.lockTenant(tenant.id());
        if (locked == null || (workflow.action() == TenantLifecycleAction.CLOSE
                ? !"ACTIVE".equals(locked.status()) && !"SUSPENDED".equals(locked.status())
                : !(workflow.action() == TenantLifecycleAction.SUSPEND ? "ACTIVE" : "SUSPENDED")
                        .equals(locked.status()))
                || mapper.transitionTenant(tenant.id(), locked.status(), tenant.status().name(),
                TenantAccessTime.asOffsetDateTime(tenant.updatedAt())) != 1) {
            throw new TenantStateTransitionNotAllowedException();
        }
        TenantLifecycleResult result = new TenantLifecycleResult(
                tenant.id(), tenant.displayName(), tenant.status(), tenant.expiresAt(),
                tenant.createdAt(), tenant.updatedAt());
        String response = objectMapper.writeValueAsString(result);
        if (mapper.complete(workflow.workflowId(), workflow.fencingToken(), revokedFamilyCount,
                revokedJtiCount, response, TenantAccessTime.asOffsetDateTime(at)) != 1) {
            throw staleLease();
        }
        mapper.completeRecoveries(workflow.workflowId(), response, TenantAccessTime.asOffsetDateTime(at));
        if (suspensionEvent != null) {
            TenantAccessOutboxRow outbox = new TenantAccessOutboxRow(
                    suspensionEvent.eventId(), suspensionEvent.tenantId(),
                    TenantAccessTime.asOffsetDateTime(suspensionEvent.occurredAt()), suspensionEvent.topic(),
                    suspensionEvent.orderingKey(), suspensionEvent.traceId(), suspensionEvent.eventSnapshot());
            if (creationMapper.insertOutbox(outbox) != 1) {
                throw new IllegalStateException("Tenant Suspended Outbox Event 保存失败");
            }
        }
        return result;
    }

    private TenantLifecycleWorkflow toDomain(TenantLifecycleRow row) {
        TenantLifecycleResult result = row.getResponseBody() == null ? null
                : objectMapper.readValue(row.getResponseBody(), TenantLifecycleResult.class);
        return new TenantLifecycleWorkflow(
                row.getWorkflowId(), row.getTenantId(), row.getActorIdentityId(), row.getIdempotencyKey(),
                row.getRequestFingerprint(), TenantLifecycleAction.valueOf(row.getLifecycleAction()),
                row.getRevocationRequestId(), row.getReleaseRequestId(),
                TenantLifecycleStatus.valueOf(row.getWorkflowStatus()), row.isFenceEstablished(),
                row.getRevokedFamilyCount(), row.getRevokedJtiCount(), row.getAttemptCount(),
                TenantAccessTime.asInstant(row.getNextAttemptAt()), row.getLeaseOwner(),
                TenantAccessTime.asInstant(row.getLeaseUntil()), row.getFencingToken(),
                TenantAccessTime.asInstant(row.getRecoveryStartedAt()),
                TenantAccessTime.asInstant(row.getIamRecoveryConfirmedAt()),
                TenantAccessTime.asInstant(row.getCompletedAt()), result);
    }

    private static Tenant toDomain(TenantRow row) {
        return new Tenant(row.id(), row.displayName(), TenantStatus.valueOf(row.status()),
                TenantAccessTime.asInstant(row.expiresAt()), TenantAccessTime.asInstant(row.createdAt()),
                TenantAccessTime.asInstant(row.updatedAt()));
    }

    private void setTarget(UUID tenantId) {
        if (!tenantId.toString().equals(mapper.setOperationTarget(tenantId))) {
            throw new IllegalStateException("Tenant Lifecycle Operation Target 设置失败");
        }
    }

    private static TenantLifecycleException failure(String code, String message) {
        return new TenantLifecycleException(code, message);
    }

    private static IllegalStateException staleLease() {
        return new IllegalStateException("Tenant Lifecycle Workflow 租约已失效");
    }
}
