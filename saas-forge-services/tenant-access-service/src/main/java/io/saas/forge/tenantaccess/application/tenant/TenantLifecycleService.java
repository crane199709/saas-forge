package io.saas.forge.tenantaccess.application.tenant;

import io.saas.forge.tenantaccess.domain.outbox.OutboxEvent;
import io.saas.forge.tenantaccess.domain.tenant.Tenant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

public final class TenantLifecycleService {
    private final TenantLifecycleRepository workflows;
    private final SessionRevocationGateway revocations;
    private final TenantSuspendedEventFactory events;
    private final UuidV7Generator ids;
    private final TenantLifecycleRecoveryPolicy policy;
    private final Clock clock;
    private final String claimant;

    public TenantLifecycleService(
            TenantLifecycleRepository workflows,
            SessionRevocationGateway revocations,
            TenantSuspendedEventFactory events,
            UuidV7Generator ids,
            TenantLifecycleRecoveryPolicy policy,
            Clock clock,
            String claimant) {
        this.workflows = workflows;
        this.revocations = revocations;
        this.events = events;
        this.ids = ids;
        this.policy = policy;
        this.clock = clock;
        this.claimant = claimant;
    }

    public TenantLifecycleResult suspend(
            UUID actorIdentityId, UUID idempotencyKey, UUID tenantId, String traceId) {
        requireUuidV7(actorIdentityId, "调用方 Identity ID");
        requireUuidV7(idempotencyKey, "Idempotency-Key");
        requireUuidV7(tenantId, "Tenant ID");
        TenantLifecycleClaim claim = workflows.prepare(
                actorIdentityId, idempotencyKey, tenantId, TenantLifecycleAction.SUSPEND,
                fingerprint("POST", "/api/v1/platform/tenants/" + tenantId + "/suspensions"),
                ids.next(), ids.next(), null, clock.instant());
        return resumeOrReplay(claim.workflow(), traceId, true);
    }

    public TenantLifecycleResult resume(
            UUID actorIdentityId, UUID idempotencyKey, UUID tenantId) {
        requireUuidV7(actorIdentityId, "调用方 Identity ID");
        requireUuidV7(idempotencyKey, "Idempotency-Key");
        requireUuidV7(tenantId, "Tenant ID");
        TenantLifecycleClaim claim = workflows.prepare(
                actorIdentityId, idempotencyKey, tenantId, TenantLifecycleAction.RESUME,
                fingerprint("DELETE", "/api/v1/platform/tenants/" + tenantId + "/suspensions"),
                ids.next(), null, ids.next(), clock.instant());
        return resumeOrReplay(claim.workflow(), null, true);
    }

    public TenantLifecycleResult close(
            UUID actorIdentityId, UUID idempotencyKey, UUID tenantId, String traceId) {
        requireUuidV7(actorIdentityId, "调用方 Identity ID");
        requireUuidV7(idempotencyKey, "Idempotency-Key");
        requireUuidV7(tenantId, "Tenant ID");
        TenantLifecycleClaim claim = workflows.prepare(
                actorIdentityId, idempotencyKey, tenantId, TenantLifecycleAction.CLOSE,
                fingerprint("POST", "/api/v1/platform/tenants/" + tenantId + "/closures"),
                ids.next(), ids.next(), null, clock.instant());
        return resumeOrReplay(claim.workflow(), traceId, true);
    }

    public TenantLifecycleResult recoverClosure(
            UUID actorIdentityId, UUID idempotencyKey, UUID tenantId, String traceId) {
        requireUuidV7(actorIdentityId, "调用方 Identity ID");
        requireUuidV7(idempotencyKey, "Idempotency-Key");
        requireUuidV7(tenantId, "Tenant ID");
        TenantLifecycleClaim claim = workflows.prepareClosureRecovery(
                actorIdentityId, idempotencyKey, tenantId,
                fingerprint("POST", "/api/v1/platform/tenants/" + tenantId + "/closure-recoveries"),
                clock.instant());
        return resumeOrReplay(claim.workflow(), traceId, true);
    }

    public TenantLifecycleResult recoverSuspension(
            UUID actorIdentityId, UUID idempotencyKey, UUID tenantId, String traceId) {
        requireUuidV7(actorIdentityId, "调用方 Identity ID");
        requireUuidV7(idempotencyKey, "Idempotency-Key");
        requireUuidV7(tenantId, "Tenant ID");
        TenantLifecycleClaim claim = workflows.prepareRecovery(
                actorIdentityId, idempotencyKey, tenantId,
                fingerprint("POST", "/api/v1/platform/tenants/" + tenantId + "/suspension-recoveries"),
                clock.instant());
        return resumeOrReplay(claim.workflow(), traceId, true);
    }

    public void recoverNext() {
        Instant now = clock.instant();
        workflows.claimNext(claimant, now, now.plus(policy.leaseDuration()), policy.maximumAttempts())
                .ifPresent(workflow -> processClaimed(workflow, null, false));
    }

    public TenantLifecycleProgress read(UUID tenantId) {
        var snapshot = workflows.readLifecycle(tenantId);
        var workflow = snapshot.workflow().orElse(null);
        boolean pending = workflow != null && workflow.status() == TenantLifecycleStatus.PENDING;
        boolean recovery = workflow != null && workflow.status() == TenantLifecycleStatus.RECOVERY_REQUIRED;
        boolean free = !pending && !recovery;
        return new TenantLifecycleProgress(tenantId, workflow == null ? null : workflow.workflowId(),
                workflow == null ? null : workflow.action().name(),
                workflow == null ? "NONE" : workflow.status().name(),
                free && snapshot.tenant().status() == io.saas.forge.tenantaccess.domain.tenant.TenantStatus.ACTIVE,
                free && snapshot.tenant().status() == io.saas.forge.tenantaccess.domain.tenant.TenantStatus.SUSPENDED,
                recovery && workflow.action() == TenantLifecycleAction.SUSPEND, pending,
                free && (snapshot.tenant().status() == io.saas.forge.tenantaccess.domain.tenant.TenantStatus.ACTIVE
                        || snapshot.tenant().status() == io.saas.forge.tenantaccess.domain.tenant.TenantStatus.SUSPENDED),
                recovery && workflow.action() == TenantLifecycleAction.CLOSE);
    }

    public TenantLifecycleResult continueOperation(UUID tenantId, UUID operationId, String traceId) {
        var workflow = workflows.readLifecycle(tenantId).workflow()
                .filter(value -> value.workflowId().equals(operationId))
                .orElseThrow(() -> new TenantLifecycleException("TENANT_LIFECYCLE_OPERATION_UNAVAILABLE",
                        "当前 Tenant 生命周期操作不可继续"));
        return resumeOrReplay(workflow, traceId, true);
    }

    private TenantLifecycleResult resumeOrReplay(
            TenantLifecycleWorkflow workflow, String traceId, boolean interactive) {
        TenantLifecycleResult terminal = terminal(workflow);
        if (terminal != null) return terminal;
        Instant now = clock.instant();
        var claimed = workflows.claim(
                workflow.workflowId(), claimant, now, now.plus(policy.leaseDuration()), policy.maximumAttempts());
        if (claimed.isEmpty()) {
            TenantLifecycleWorkflow current = workflows.find(workflow.workflowId()).orElseThrow();
            TenantLifecycleResult currentResult = terminal(current);
            if (currentResult != null) return currentResult;
            throw pending(current, now);
        }
        return processClaimed(claimed.orElseThrow(), traceId, interactive);
    }

    private TenantLifecycleResult processClaimed(
            TenantLifecycleWorkflow workflow, String traceId, boolean interactive) {
        try {
            if (workflow.explicitRecoveryPending()) {
                if (workflow.action() == TenantLifecycleAction.CLOSE) {
                    try {
                        // 关闭也可沿用已完成的冻结 Fence；先查原请求，只有 IAM 要求时才恢复撤销。
                        revocations.revoke(workflow.revocationRequestId(), workflow.tenantId());
                    } catch (SessionRevocationRejectedException exception) {
                        revocations.recover(workflow.revocationRequestId(), workflow.tenantId());
                    }
                } else {
                    revocations.recover(workflow.revocationRequestId(), workflow.tenantId());
                }
                workflows.confirmIamRecovery(workflow, clock.instant());
            }
            return switch (workflow.action()) {
                case SUSPEND -> suspendClaimed(workflow, traceId);
                case RESUME -> resumeClaimed(workflow);
                case CLOSE -> closeClaimed(workflow, traceId);
            };
        } catch (TenantLifecycleException exception) {
            if (interactive) throw exception;
            return null;
        } catch (SessionRevocationUnavailableException exception) {
            return handleFailure(workflow, "IAM_REVOCATION_UNAVAILABLE", true, interactive);
        } catch (RuntimeException exception) {
            return handleFailure(workflow, "IAM_REVOCATION_REJECTED", false, interactive);
        }
    }

    private TenantLifecycleResult suspendClaimed(TenantLifecycleWorkflow workflow, String traceId) {
        workflows.markRevocationAttempt(workflow);
        SessionRevocationGateway.Result remote = revocations.revoke(
                workflow.revocationRequestId(), workflow.tenantId());
        workflows.confirmFence(workflow);
        if (remote.status() == SessionRevocationGateway.Result.Status.PENDING) {
            workflows.schedulePending(workflow, clock.instant().plusSeconds(remote.retryAfterSeconds()));
            throw pending(remote.retryAfterSeconds(), "TENANT_SUSPENSION_PENDING");
        }
        Tenant tenant = workflows.loadTenant(workflow.tenantId()).suspend(clock.instant());
        OutboxEvent event = events.create(
                tenant, workflow.actorIdentityId(), remote.revokedFamilyCount(), clock.instant(), traceId);
        return workflows.complete(workflow, tenant, remote.revokedFamilyCount(), remote.revokedJtiCount(),
                clock.instant(), event);
    }

    private TenantLifecycleResult closeClaimed(TenantLifecycleWorkflow workflow, String traceId) {
        workflows.markRevocationAttempt(workflow);
        SessionRevocationGateway.Result remote = revocations.revoke(
                workflow.revocationRequestId(), workflow.tenantId());
        workflows.confirmFence(workflow);
        if (remote.status() == SessionRevocationGateway.Result.Status.PENDING) {
            workflows.schedulePending(workflow, clock.instant().plusSeconds(remote.retryAfterSeconds()));
            throw pending(remote.retryAfterSeconds(), "TENANT_CLOSURE_PENDING");
        }
        Tenant before = workflows.loadTenant(workflow.tenantId());
        Tenant tenant = before.close(clock.instant());
        OutboxEvent event = events.createClosure(
                tenant, workflow.actorIdentityId(),
                before.status() == io.saas.forge.tenantaccess.domain.tenant.TenantStatus.SUSPENDED
                        ? 0 : remote.revokedFamilyCount(), clock.instant(), traceId);
        return workflows.complete(workflow, tenant, remote.revokedFamilyCount(), remote.revokedJtiCount(),
                clock.instant(), event);
    }

    private TenantLifecycleResult resumeClaimed(TenantLifecycleWorkflow workflow) {
        revocations.release(workflow.releaseRequestId(), workflow.revocationRequestId(), workflow.tenantId());
        Tenant tenant = workflows.loadTenant(workflow.tenantId()).resume(clock.instant());
        return workflows.complete(workflow, tenant, workflow.revokedFamilyCount(), workflow.revokedJtiCount(),
                clock.instant(), null);
    }

    private TenantLifecycleResult handleFailure(
            TenantLifecycleWorkflow workflow, String failure, boolean fenceMayExist, boolean interactive) {
        Instant now = clock.instant();
        workflows.scheduleFailure(
                workflow, now, now.plus(policy.retryDelay()), failure, policy.maximumAttempts(), fenceMayExist);
        TenantLifecycleWorkflow current = workflows.find(workflow.workflowId()).orElseThrow();
        TenantLifecycleResult terminal = terminal(current);
        if (terminal != null) return terminal;
        if (!interactive) return null;
        throw pending(current, now);
    }

    private static TenantLifecycleResult terminal(TenantLifecycleWorkflow workflow) {
        return switch (workflow.status()) {
            case COMPLETED -> workflow.result();
            case RETRY_REQUIRED -> throw new TenantLifecycleException(
                    workflow.action() == TenantLifecycleAction.CLOSE
                            ? "TENANT_CLOSURE_RETRY_REQUIRED" : "TENANT_SUSPENSION_RETRY_REQUIRED",
                    "Tenant 生命周期自动恢复已耗尽，请使用新 Idempotency-Key 重试");
            case RECOVERY_REQUIRED -> throw new TenantLifecycleException(
                    workflow.action() == TenantLifecycleAction.CLOSE
                            ? "TENANT_CLOSURE_RECOVERY_REQUIRED" : "TENANT_SUSPENSION_RECOVERY_REQUIRED",
                    "Tenant 撤销阻断保持生效，需要 Platform Admin 显式继续");
            case PENDING -> null;
        };
    }

    private static TenantLifecycleException pending(TenantLifecycleWorkflow workflow, Instant now) {
        String code = workflow.recoveryStartedAt() == null
                ? switch (workflow.action()) {
                    case SUSPEND -> "TENANT_SUSPENSION_PENDING";
                    case RESUME -> "TENANT_RESUME_PENDING";
                    case CLOSE -> "TENANT_CLOSURE_PENDING";
                }
                : workflow.action() == TenantLifecycleAction.CLOSE
                        ? "TENANT_CLOSURE_RECOVERY_PENDING" : "TENANT_SUSPENSION_RECOVERY_PENDING";
        long retryAfter = Math.max(1, Duration.between(now, workflow.nextAttemptAt()).toSeconds());
        return pending(retryAfter, code);
    }

    private static TenantLifecycleException pending(long retryAfter, String code) {
        return new TenantLifecycleException(code, "Tenant 生命周期变更尚未完成", Math.max(1, retryAfter));
    }

    static String fingerprint(String method, String path) {
        try {
            String canonical = method + "\n" + path + "\n";
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境不支持 SHA-256", exception);
        }
    }

    private static void requireUuidV7(UUID value, String field) {
        if (value == null || value.version() != 7) {
            if ("Idempotency-Key".equals(field)) throw new IdempotencyKeyInvalidException();
            throw new IllegalArgumentException(field + " 必须是 UUIDv7");
        }
    }
}
