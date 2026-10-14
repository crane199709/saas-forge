package io.saas.forge.tenantaccess.application.membership;

import io.saas.forge.sdk.tenant.TenantContextSnapshot;
import io.saas.forge.tenantaccess.application.authorization.PermissionAuthority;
import io.saas.forge.tenantaccess.application.tenant.SessionRevocationGateway.Result;
import io.saas.forge.tenantaccess.application.tenant.SessionRevocationRejectedException;
import io.saas.forge.tenantaccess.application.tenant.UuidV7Generator;
import io.saas.forge.tenantaccess.domain.outbox.OutboxEvent;
import io.saas.forge.tenantaccess.domain.outbox.OutboxEventRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Membership 禁用根工作流；每个远程副作用均由已提交的稳定操作 ID 恢复。 */
public final class MembershipDisableService {
    private static final String MANAGE_PERMISSION = "tenant-access:membership:disable";
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final Duration RETRY = Duration.ofSeconds(2);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final PermissionAuthority permissions;
    private final MembershipRevocationGateway revocations;
    private final MembershipQuotaGateway quota;
    private final OutboxEventRepository outbox;
    private final ObjectMapper json;
    private final UuidV7Generator ids;
    private final Clock clock;
    private final String topic;

    public MembershipDisableService(JdbcTemplate jdbc, PlatformTransactionManager manager,
            PermissionAuthority permissions, MembershipRevocationGateway revocations,
            MembershipQuotaGateway quota, OutboxEventRepository outbox, ObjectMapper json,
            UuidV7Generator ids, Clock clock, String topic) {
        this.jdbc = jdbc; this.transactions = new TransactionTemplate(manager);
        this.permissions = permissions; this.revocations = revocations; this.quota = quota;
        this.outbox = outbox; this.json = json; this.ids = ids; this.clock = clock; this.topic = topic;
    }

    public Progress disable(TenantContextSnapshot actor, UUID membershipId, UUID operationId, String traceId) {
        requireV7(membershipId);
        requireV7(operationId);
        Work work = transactions.execute(status -> prepare(actor, membershipId, operationId));
        return advance(work, traceId);
    }

    public Progress read(TenantContextSnapshot actor, UUID membershipId) {
        requireV7(membershipId);
        return transactions.execute(status -> {
            target(actor.tenantId());
            authorize(actor, membershipId);
            return progress(latest(actor.tenantId(), membershipId));
        });
    }

    public Progress recover(TenantContextSnapshot actor, UUID membershipId, String traceId) {
        requireV7(membershipId);
        Work work = transactions.execute(status -> {
            target(actor.tenantId());
            lockTenant(actor.tenantId());
            authorize(actor, membershipId);
            Work current = latest(actor.tenantId(), membershipId);
            if (current == null || !"RECOVERY_REQUIRED".equals(current.state()))
                throw new MembershipDisableFailure("MEMBERSHIP_DISABLE_RECOVERY_UNAVAILABLE");
            return current;
        });
        // IAM 的原 Fence 代际必须保持，不能创建新的撤销请求或清除旧拒绝事实。
        revocations.recover(work.revocationId(), work.tenantId(), work.membershipId());
        transactions.executeWithoutResult(status -> {
            target(work.tenantId());
            jdbc.update("""
                    UPDATE membership_disable_workflows SET workflow_status = 'REVOKING',
                      next_attempt_at = ?, lease_until = NULL, last_failure = NULL, updated_at = ?
                    WHERE operation_id = ? AND workflow_status = 'RECOVERY_REQUIRED'
                    """, sql(now()), sql(now()), work.operationId());
        });
        return advance(work, traceId);
    }

    public void recoverNext() {
        Work claimed = transactions.execute(status -> {
            Instant now = now();
            List<Work> rows = jdbc.query("SELECT tenant_id, operation_id FROM claim_next_membership_disable(?, ?)",
                    (row, index) -> {
                        UUID tenantId = row.getObject(1, UUID.class);
                        target(tenantId);
                        return find(tenantId, row.getObject(2, UUID.class));
                    }, sql(now), sql(now.plus(LEASE)));
            return rows.isEmpty() ? null : rows.get(0);
        });
        if (claimed != null) process(claimed, null);
    }

    private Work prepare(TenantContextSnapshot actor, UUID membershipId, UUID operationId) {
        target(actor.tenantId());
        lockTenant(actor.tenantId());
        Work previous = find(actor.tenantId(), operationId);
        if (previous != null) {
            if (!previous.membershipId().equals(membershipId) || !previous.actorIdentityId().equals(actor.identityId()))
                throw new MembershipDisableFailure("IDEMPOTENCY_KEY_REUSED");
            return previous;
        }
        authorize(actor, membershipId);
        List<String> statuses = jdbc.query("""
                SELECT membership_status FROM memberships WHERE tenant_id = ? AND id = ? FOR UPDATE
                """, (row, index) -> row.getString(1), actor.tenantId(), membershipId);
        if (statuses.isEmpty()) throw new MembershipDisableFailure("MEMBERSHIP_NOT_FOUND");
        if (!"ENABLED".equals(statuses.get(0))) throw new MembershipDisableFailure("MEMBERSHIP_ALREADY_DISABLED");
        if (latestActive(actor.tenantId(), membershipId) != null)
            throw new MembershipDisableFailure("MEMBERSHIP_DISABLE_IN_PROGRESS");
        if (administrator(actor.tenantId(), membershipId)) {
            if (!permissions.isAdministrator(actor))
                throw new MembershipDisableFailure("MEMBERSHIP_ADMINISTRATOR_PROTECTED");
            if (usableAdministrators(actor.tenantId()) <= 1)
                throw new MembershipDisableFailure("LAST_TENANT_ADMINISTRATOR");
        }
        Instant now = now();
        Work created = new Work(operationId, actor.tenantId(), membershipId, actor.membershipId(),
                actor.identityId(), ids.next(), ids.next(), "REVOKING", 0, 0);
        jdbc.update("""
                INSERT INTO membership_disable_workflows
                (operation_id, tenant_id, membership_id, actor_membership_id, actor_identity_id,
                 revocation_request_id, release_operation_id, workflow_status, next_attempt_at,
                 created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, 'REVOKING', ?, ?, ?)
                """, created.operationId(), created.tenantId(), created.membershipId(),
                created.actorMembershipId(), created.actorIdentityId(), created.revocationId(),
                created.releaseId(), sql(now), sql(now), sql(now));
        return created;
    }

    private Progress advance(Work work, String traceId) {
        Work claimed = transactions.execute(status -> {
            target(work.tenantId());
            int changed = jdbc.update("""
                    UPDATE membership_disable_workflows SET lease_until = ?, attempt_count = attempt_count + 1,
                      updated_at = ? WHERE operation_id = ? AND workflow_status IN ('REVOKING', 'RELEASE_PENDING')
                      AND (lease_until IS NULL OR lease_until <= ?)
                    """, sql(now().plus(LEASE)), sql(now()), work.operationId(), sql(now()));
            return changed == 0 ? null : find(work.tenantId(), work.operationId());
        });
        if (claimed == null) return progress(transactions.execute(status -> {
            target(work.tenantId());
            return find(work.tenantId(), work.operationId());
        }));
        return process(claimed, traceId);
    }

    private Progress process(Work work, String traceId) {
        try {
            if ("REVOKING".equals(work.state())) {
                Result result = revocations.revoke(work.revocationId(), work.tenantId(), work.membershipId());
                if (result.status() == Result.Status.PENDING) {
                    schedule(work, "IAM_REVOCATION_PENDING", Math.max(1, result.retryAfterSeconds()));
                    return readProgress(work);
                }
                Work revoking = work;
                work = transactions.execute(status -> commitDisabled(revoking, result, traceId));
            }
            if ("RELEASE_PENDING".equals(work.state())) {
                quota.release(work.tenantId(), work.releaseId());
                Work released = work;
                transactions.executeWithoutResult(status -> {
                    target(released.tenantId());
                    jdbc.update("""
                            UPDATE membership_disable_workflows SET workflow_status = 'COMPLETED',
                              lease_until = NULL, last_failure = NULL, updated_at = ?
                            WHERE operation_id = ? AND workflow_status = 'RELEASE_PENDING'
                            """, sql(now()), released.operationId());
                });
            }
        } catch (SessionRevocationRejectedException rejected) {
            markRecoveryRequired(work, "IAM_REVOCATION_RECOVERY_REQUIRED");
        } catch (RuntimeException failure) {
            schedule(work, "DEPENDENCY_UNAVAILABLE", RETRY.toSeconds());
        }
        return readProgress(work);
    }

    private Work commitDisabled(Work work, Result result, String traceId) {
        target(work.tenantId());
        lockTenant(work.tenantId());
        Work current = find(work.tenantId(), work.operationId());
        if (!"REVOKING".equals(current.state())) return current;
        if (administrator(work.tenantId(), work.membershipId()) && usableAdministrators(work.tenantId()) < 1)
            throw new MembershipDisableFailure("LAST_TENANT_ADMINISTRATOR");
        int updated = jdbc.update("""
                UPDATE memberships SET membership_status = 'DISABLED'
                WHERE tenant_id = ? AND id = ? AND membership_status = 'ENABLED'
                """, work.tenantId(), work.membershipId());
        if (updated != 1) throw new MembershipDisableFailure("MEMBERSHIP_DISABLE_CONFLICT");
        Instant now = now();
        jdbc.update("""
                UPDATE membership_disable_workflows SET workflow_status = 'RELEASE_PENDING',
                  revoked_family_count = ?, revoked_jti_count = ?, next_attempt_at = ?, updated_at = ?
                WHERE operation_id = ?
                """, result.revokedFamilyCount(), result.revokedJtiCount(), sql(now), sql(now), work.operationId());
        outbox.append(disabledEvent(work, result.revokedFamilyCount(), now, traceId));
        return find(work.tenantId(), work.operationId());
    }

    private void schedule(Work work, String reason, long seconds) {
        transactions.executeWithoutResult(status -> {
            target(work.tenantId());
            jdbc.update("""
                    UPDATE membership_disable_workflows SET next_attempt_at = ?, lease_until = NULL,
                      last_failure = ?, updated_at = ? WHERE operation_id = ?
                      AND workflow_status IN ('REVOKING', 'RELEASE_PENDING')
                    """, sql(now().plusSeconds(seconds)), reason, sql(now()), work.operationId());
        });
    }

    private void markRecoveryRequired(Work work, String reason) {
        transactions.executeWithoutResult(status -> {
            target(work.tenantId());
            jdbc.update("""
                    UPDATE membership_disable_workflows SET workflow_status = 'RECOVERY_REQUIRED',
                      lease_until = NULL, last_failure = ?, updated_at = ?
                    WHERE operation_id = ? AND workflow_status = 'REVOKING'
                    """, reason, sql(now()), work.operationId());
        });
    }

    private Progress readProgress(Work work) {
        return transactions.execute(status -> {
            target(work.tenantId());
            return progress(find(work.tenantId(), work.operationId()));
        });
    }

    private void authorize(TenantContextSnapshot actor, UUID targetMembershipId) {
        boolean admin = permissions.isAdministrator(actor);
        if (!admin && !permissions.allowed(actor, MANAGE_PERMISSION))
            throw new MembershipDisableFailure("MEMBERSHIP_DISABLE_FORBIDDEN");
        if (administrator(actor.tenantId(), targetMembershipId) && !admin)
            throw new MembershipDisableFailure("MEMBERSHIP_ADMINISTRATOR_PROTECTED");
    }

    private boolean administrator(UUID tenantId, UUID membershipId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM membership_role_assignments assignment
                JOIN tenant_roles role ON role.tenant_id = assignment.tenant_id AND role.id = assignment.role_id
                WHERE assignment.tenant_id = ? AND assignment.membership_id = ?
                  AND role.role_key = 'TENANT_ADMINISTRATOR' AND role.system_managed)
                """, Boolean.class, tenantId, membershipId));
    }

    private int usableAdministrators(UUID tenantId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM memberships member
                JOIN membership_role_assignments assignment
                  ON assignment.tenant_id = member.tenant_id AND assignment.membership_id = member.id
                JOIN tenant_roles role ON role.tenant_id = assignment.tenant_id AND role.id = assignment.role_id
                WHERE member.tenant_id = ? AND member.membership_status = 'ENABLED'
                  AND role.role_key = 'TENANT_ADMINISTRATOR' AND role.system_managed
                  AND NOT EXISTS (SELECT 1 FROM membership_disable_workflows work
                                  WHERE work.tenant_id = member.tenant_id AND work.membership_id = member.id
                                    AND work.workflow_status IN ('REVOKING', 'RECOVERY_REQUIRED', 'RELEASE_PENDING'))
                """, Integer.class, tenantId);
        return count == null ? 0 : count;
    }

    private Work latestActive(UUID tenantId, UUID membershipId) {
        List<Work> rows = jdbc.query(WORK_SELECT + " AND membership_id = ? AND workflow_status <> 'COMPLETED'",
                MembershipDisableService::work, tenantId, membershipId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Work latest(UUID tenantId, UUID membershipId) {
        List<Work> rows = jdbc.query(WORK_SELECT + " AND membership_id = ? ORDER BY created_at DESC LIMIT 1",
                MembershipDisableService::work, tenantId, membershipId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Work find(UUID tenantId, UUID operationId) {
        List<Work> rows = jdbc.query(WORK_SELECT + " AND operation_id = ?",
                MembershipDisableService::work, tenantId, operationId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static final String WORK_SELECT = """
            SELECT operation_id, tenant_id, membership_id, actor_membership_id, actor_identity_id,
              revocation_request_id, release_operation_id, workflow_status,
              revoked_family_count, revoked_jti_count
            FROM membership_disable_workflows WHERE tenant_id = ?
            """;

    private static Work work(java.sql.ResultSet row, int index) throws java.sql.SQLException {
        return new Work(row.getObject(1, UUID.class), row.getObject(2, UUID.class),
                row.getObject(3, UUID.class), row.getObject(4, UUID.class), row.getObject(5, UUID.class),
                row.getObject(6, UUID.class), row.getObject(7, UUID.class), row.getString(8),
                row.getLong(9), row.getLong(10));
    }

    private void lockTenant(UUID tenantId) {
        List<UUID> rows = jdbc.query("SELECT id FROM tenants WHERE id = ? FOR UPDATE",
                (row, index) -> row.getObject(1, UUID.class), tenantId);
        if (rows.isEmpty()) throw new MembershipDisableFailure("TENANT_NOT_FOUND");
    }

    private void target(UUID tenantId) {
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
    }

    private OutboxEvent disabledEvent(Work work, long revokedFamilies, Instant at, String traceId) {
        UUID eventId = ids.next();
        UUID identityId = jdbc.queryForObject("""
                SELECT identity_id FROM memberships WHERE tenant_id = ? AND id = ?
                """, UUID.class, work.tenantId(), work.membershipId());
        Map<String, Object> data = Map.of("tenantId", work.tenantId().toString(),
                "membershipId", work.membershipId().toString(), "identityId", identityId.toString(),
                "actorIdentityId", work.actorIdentityId().toString(), "quotaReleasePending", true);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("specversion", "1.0");
        envelope.put("id", eventId.toString());
        envelope.put("source", "urn:saas.forge:tenant-access-service");
        envelope.put("type", "com.saas.forge.membership.disabled.v1");
        envelope.put("subject", work.membershipId().toString());
        envelope.put("time", at.toString());
        envelope.put("datacontenttype", "application/json");
        envelope.put("dataschema", "https://saas.forge.io/contracts/events/membership-disabled.v1.schema.json");
        if (traceId != null) envelope.put("traceId", traceId);
        envelope.put("data", data);
        return new OutboxEvent(eventId, work.tenantId(), at, topic, work.tenantId().toString(),
                traceId, json.writeValueAsString(envelope));
    }

    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MILLIS); }
    private static OffsetDateTime sql(Instant instant) { return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC); }

    private static Progress progress(Work work) {
        if (work == null) return new Progress(null, "NONE", "ENABLED", false, false);
        boolean disabled = "RELEASE_PENDING".equals(work.state()) || "COMPLETED".equals(work.state());
        return new Progress(work.operationId(), work.state(), disabled ? "DISABLED" : "ENABLED",
                !"COMPLETED".equals(work.state()), "RECOVERY_REQUIRED".equals(work.state()));
    }

    private static void requireV7(UUID id) {
        if (id == null || id.version() != 7) throw new MembershipDisableFailure("INVALID_UUID_V7");
    }

    private record Work(UUID operationId, UUID tenantId, UUID membershipId, UUID actorMembershipId,
            UUID actorIdentityId, UUID revocationId, UUID releaseId, String state,
            long revokedFamilies, long revokedJtis) {}

    public record Progress(UUID operationId, String operationStatus, String membershipStatus,
            boolean pending, boolean recoveryRequired) {}
}
