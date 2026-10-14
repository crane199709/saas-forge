package io.saas.forge.tenantaccess.application.membership;

import io.saas.forge.sdk.tenant.TenantContextSnapshot;
import io.saas.forge.tenantaccess.application.authorization.PermissionAuthority;
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

/** Membership 启用根工作流；每个 Quota 与 Fence 副作用使用已提交的独立稳定 ID。 */
public final class MembershipEnableService {
    private static final String MANAGE_PERMISSION = "tenant-access:membership:enable";
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final Duration RETRY = Duration.ofSeconds(2);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final PermissionAuthority permissions;
    private final MembershipEnableQuotaGateway quota;
    private final MembershipEnableFenceGateway fences;
    private final OutboxEventRepository outbox;
    private final ObjectMapper json;
    private final UuidV7Generator ids;
    private final Clock clock;
    private final String topic;

    public MembershipEnableService(JdbcTemplate jdbc, PlatformTransactionManager manager,
            PermissionAuthority permissions, MembershipEnableQuotaGateway quota,
            MembershipEnableFenceGateway fences, OutboxEventRepository outbox, ObjectMapper json,
            UuidV7Generator ids, Clock clock, String topic) {
        this.jdbc = jdbc; this.transactions = new TransactionTemplate(manager);
        this.permissions = permissions; this.quota = quota; this.fences = fences;
        this.outbox = outbox; this.json = json; this.ids = ids; this.clock = clock; this.topic = topic;
    }

    public Progress enable(TenantContextSnapshot actor, UUID membershipId, UUID operationId, String traceId) {
        requireV7(membershipId); requireV7(operationId);
        Work work = transactions.execute(status -> prepare(actor, membershipId, operationId));
        return advance(work, traceId);
    }

    public Progress read(TenantContextSnapshot actor, UUID membershipId) {
        requireV7(membershipId);
        return transactions.execute(status -> {
            target(actor.tenantId());
            authorize(actor, membershipId);
            String statusValue = membershipStatus(actor.tenantId(), membershipId);
            Work work = latest(actor.tenantId(), membershipId);
            return work == null ? new Progress(null, "NONE", statusValue, false)
                    : progress(work, statusValue);
        });
    }

    public void recoverNext() {
        Work claimed = transactions.execute(status -> {
            Instant at = now();
            List<Work> rows = jdbc.query("SELECT tenant_id, operation_id FROM claim_next_membership_enable(?, ?)",
                    (row, index) -> {
                        UUID tenantId = row.getObject(1, UUID.class);
                        target(tenantId);
                        return find(tenantId, row.getObject(2, UUID.class));
                    }, sql(at), sql(at.plus(LEASE)));
            return rows.isEmpty() ? null : rows.get(0);
        });
        if (claimed != null) process(claimed, null);
    }

    private Work prepare(TenantContextSnapshot actor, UUID membershipId, UUID operationId) {
        target(actor.tenantId());
        lockTenant(actor.tenantId());
        authorize(actor, membershipId);
        Work previous = find(actor.tenantId(), operationId);
        if (previous != null) {
            if (!previous.membershipId().equals(membershipId)
                    || !previous.actorIdentityId().equals(actor.identityId()))
                throw new MembershipEnableFailure("IDEMPOTENCY_KEY_REUSED");
            return previous;
        }
        String statusValue = membershipStatus(actor.tenantId(), membershipId);
        if (active(actor.tenantId(), membershipId))
            throw new MembershipEnableFailure("MEMBERSHIP_ENABLE_IN_PROGRESS");
        if (!"DISABLED".equals(statusValue)) throw new MembershipEnableFailure("MEMBERSHIP_ALREADY_ENABLED");
        List<Disable> disables = jdbc.query("""
                SELECT operation_id, revocation_request_id, workflow_status
                FROM membership_disable_workflows WHERE tenant_id = ? AND membership_id = ?
                ORDER BY created_at DESC, operation_id DESC LIMIT 1
                """, (row, index) -> new Disable(row.getObject(1, UUID.class),
                row.getObject(2, UUID.class), row.getString(3)), actor.tenantId(), membershipId);
        if (disables.isEmpty() || !"COMPLETED".equals(disables.get(0).status()))
            throw new MembershipEnableFailure("MEMBERSHIP_DISABLE_IN_PROGRESS");
        if (administrator(actor.tenantId(), membershipId) && !permissions.isAdministrator(actor))
            throw new MembershipEnableFailure("MEMBERSHIP_ADMINISTRATOR_PROTECTED");
        Disable disable = disables.get(0);
        Instant at = now();
        Work work = new Work(operationId, actor.tenantId(), membershipId, actor.membershipId(),
                actor.identityId(), disable.id(), disable.revocationId(), ids.next(), ids.next(), ids.next(),
                "CONSUMING");
        jdbc.update("""
                INSERT INTO membership_enable_workflows
                (operation_id, tenant_id, membership_id, actor_membership_id, actor_identity_id,
                 disable_operation_id, revocation_request_id, consume_operation_id,
                 compensation_operation_id, fence_release_request_id, workflow_status,
                 next_attempt_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'CONSUMING', ?, ?, ?)
                """, work.operationId(), work.tenantId(), work.membershipId(), work.actorMembershipId(),
                work.actorIdentityId(), work.disableId(), work.revocationId(), work.consumeId(),
                work.compensationId(), work.fenceReleaseId(), sql(at), sql(at), sql(at));
        return work;
    }

    private Progress advance(Work work, String traceId) {
        Work claimed = transactions.execute(status -> {
            target(work.tenantId());
            int changed = jdbc.update("""
                    UPDATE membership_enable_workflows SET lease_until = ?, attempt_count = attempt_count + 1,
                      updated_at = ? WHERE operation_id = ?
                      AND workflow_status IN ('CONSUMING', 'COMPENSATION_PENDING', 'FENCE_PENDING')
                      AND (lease_until IS NULL OR lease_until <= ?)
                    """, sql(now().plus(LEASE)), sql(now()), work.operationId(), sql(now()));
            return changed == 0 ? null : find(work.tenantId(), work.operationId());
        });
        if (claimed == null) return readProgress(work);
        return process(claimed, traceId);
    }

    private Progress process(Work work, String traceId) {
        if ("CONSUMING".equals(work.state())) {
            try {
                quota.consume(work.tenantId(), work.consumeId());
                // 一旦跨服务扣减成功，持久状态的默认恢复动作是补偿；本地提交才改为解除 Fence。
                Work consumed = work;
                work = transactions.execute(status -> {
                    target(consumed.tenantId());
                    jdbc.update("""
                            UPDATE membership_enable_workflows SET workflow_status = 'COMPENSATION_PENDING',
                              updated_at = ? WHERE operation_id = ? AND workflow_status = 'CONSUMING'
                            """, sql(now()), consumed.operationId());
                    return find(consumed.tenantId(), consumed.operationId());
                });
                try {
                    Work ready = work;
                    work = transactions.execute(status -> commitEnabled(ready, traceId));
                } catch (RuntimeException localFailure) {
                    schedule(work, "LOCAL_COMMIT_FAILED", RETRY.toSeconds());
                    return compensate(work);
                }
            } catch (MembershipQuotaExceededException exhausted) {
                rejectQuota(work);
                return readProgress(work);
            } catch (RuntimeException unavailable) {
                schedule(work, "QUOTA_OR_DATABASE_UNAVAILABLE", RETRY.toSeconds());
                return readProgress(work);
            }
        }
        if ("COMPENSATION_PENDING".equals(work.state())) return compensate(work);
        if ("FENCE_PENDING".equals(work.state())) {
            try {
                fences.release(work.fenceReleaseId(), work.revocationId(),
                        work.tenantId(), work.membershipId());
                Work released = work;
                transactions.executeWithoutResult(status -> {
                    target(released.tenantId());
                    jdbc.update("""
                            UPDATE membership_enable_workflows SET workflow_status = 'COMPLETED',
                              lease_until = NULL, last_failure = NULL, updated_at = ?
                            WHERE operation_id = ? AND workflow_status = 'FENCE_PENDING'
                            """, sql(now()), released.operationId());
                });
            } catch (RuntimeException unavailable) {
                schedule(work, "IAM_FENCE_RELEASE_UNAVAILABLE", RETRY.toSeconds());
            }
        }
        return readProgress(work);
    }

    private Work commitEnabled(Work work, String traceId) {
        target(work.tenantId());
        lockTenant(work.tenantId());
        Work current = find(work.tenantId(), work.operationId());
        if (!"COMPENSATION_PENDING".equals(current.state())) return current;
        int updated = jdbc.update("""
                UPDATE memberships SET membership_status = 'ENABLED'
                WHERE tenant_id = ? AND id = ? AND membership_status = 'DISABLED'
                """, work.tenantId(), work.membershipId());
        if (updated != 1) throw new MembershipEnableFailure("MEMBERSHIP_ENABLE_CONFLICT");
        Instant at = now();
        jdbc.update("""
                UPDATE membership_enable_workflows SET workflow_status = 'FENCE_PENDING',
                  next_attempt_at = ?, updated_at = ? WHERE operation_id = ?
                """, sql(at), sql(at), work.operationId());
        outbox.append(enabledEvent(work, at, traceId));
        return find(work.tenantId(), work.operationId());
    }

    private Progress compensate(Work work) {
        try {
            quota.release(work.tenantId(), work.compensationId());
            transactions.executeWithoutResult(status -> {
                target(work.tenantId());
                jdbc.update("""
                        UPDATE membership_enable_workflows SET workflow_status = 'COMPENSATED',
                          lease_until = NULL, last_failure = 'LOCAL_COMMIT_FAILED', updated_at = ?
                        WHERE operation_id = ? AND workflow_status = 'COMPENSATION_PENDING'
                        """, sql(now()), work.operationId());
            });
        } catch (RuntimeException unavailable) {
            schedule(work, "QUOTA_COMPENSATION_UNAVAILABLE", RETRY.toSeconds());
        }
        return readProgress(work);
    }

    private void rejectQuota(Work work) {
        transactions.executeWithoutResult(status -> {
            target(work.tenantId());
            jdbc.update("""
                    UPDATE membership_enable_workflows SET workflow_status = 'QUOTA_REJECTED',
                      lease_until = NULL, last_failure = 'MAX_USERS_EXCEEDED', updated_at = ?
                    WHERE operation_id = ? AND workflow_status = 'CONSUMING'
                    """, sql(now()), work.operationId());
        });
    }

    private void schedule(Work work, String reason, long seconds) {
        transactions.executeWithoutResult(status -> {
            target(work.tenantId());
            jdbc.update("""
                    UPDATE membership_enable_workflows SET next_attempt_at = ?, lease_until = NULL,
                      last_failure = ?, updated_at = ? WHERE operation_id = ?
                      AND workflow_status IN ('CONSUMING', 'COMPENSATION_PENDING', 'FENCE_PENDING')
                    """, sql(now().plusSeconds(seconds)), reason, sql(now()), work.operationId());
        });
    }

    private void authorize(TenantContextSnapshot actor, UUID membershipId) {
        boolean administrator = permissions.isAdministrator(actor);
        if (!administrator && !permissions.allowed(actor, MANAGE_PERMISSION))
            throw new MembershipEnableFailure("MEMBERSHIP_ENABLE_FORBIDDEN");
        if (administrator(actor.tenantId(), membershipId) && !administrator)
            throw new MembershipEnableFailure("MEMBERSHIP_ADMINISTRATOR_PROTECTED");
    }

    private boolean administrator(UUID tenantId, UUID membershipId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM membership_role_assignments assignment
                JOIN tenant_roles role ON role.tenant_id = assignment.tenant_id AND role.id = assignment.role_id
                WHERE assignment.tenant_id = ? AND assignment.membership_id = ?
                  AND role.role_key = 'TENANT_ADMINISTRATOR' AND role.system_managed)
                """, Boolean.class, tenantId, membershipId));
    }

    private boolean active(UUID tenantId, UUID membershipId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM membership_enable_workflows
                WHERE tenant_id = ? AND membership_id = ?
                  AND workflow_status IN ('CONSUMING', 'COMPENSATION_PENDING', 'FENCE_PENDING'))
                """, Boolean.class, tenantId, membershipId));
    }

    private String membershipStatus(UUID tenantId, UUID membershipId) {
        List<String> rows = jdbc.query("SELECT membership_status FROM memberships WHERE tenant_id = ? AND id = ?",
                (row, index) -> row.getString(1), tenantId, membershipId);
        if (rows.isEmpty()) throw new MembershipEnableFailure("MEMBERSHIP_NOT_FOUND");
        return rows.get(0);
    }

    private Work latest(UUID tenantId, UUID membershipId) {
        List<Work> rows = jdbc.query(WORK_SELECT + " AND membership_id = ? ORDER BY created_at DESC, operation_id DESC LIMIT 1",
                MembershipEnableService::work, tenantId, membershipId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Work find(UUID tenantId, UUID operationId) {
        List<Work> rows = jdbc.query(WORK_SELECT + " AND operation_id = ?",
                MembershipEnableService::work, tenantId, operationId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static final String WORK_SELECT = """
            SELECT operation_id, tenant_id, membership_id, actor_membership_id, actor_identity_id,
              disable_operation_id, revocation_request_id, consume_operation_id,
              compensation_operation_id, fence_release_request_id, workflow_status
            FROM membership_enable_workflows WHERE tenant_id = ?
            """;

    private static Work work(java.sql.ResultSet row, int index) throws java.sql.SQLException {
        return new Work(row.getObject(1, UUID.class), row.getObject(2, UUID.class),
                row.getObject(3, UUID.class), row.getObject(4, UUID.class), row.getObject(5, UUID.class),
                row.getObject(6, UUID.class), row.getObject(7, UUID.class), row.getObject(8, UUID.class),
                row.getObject(9, UUID.class), row.getObject(10, UUID.class), row.getString(11));
    }

    private Progress readProgress(Work work) {
        return transactions.execute(status -> {
            target(work.tenantId());
            return progress(find(work.tenantId(), work.operationId()),
                    membershipStatus(work.tenantId(), work.membershipId()));
        });
    }

    private static Progress progress(Work work, String membershipStatus) {
        return new Progress(work.operationId(), work.state(), membershipStatus,
                "CONSUMING".equals(work.state()) || "COMPENSATION_PENDING".equals(work.state())
                        || "FENCE_PENDING".equals(work.state()));
    }

    private void lockTenant(UUID tenantId) {
        List<UUID> rows = jdbc.query("SELECT id FROM tenants WHERE id = ? FOR UPDATE",
                (row, index) -> row.getObject(1, UUID.class), tenantId);
        if (rows.isEmpty()) throw new MembershipEnableFailure("TENANT_NOT_FOUND");
    }

    private void target(UUID tenantId) {
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
    }

    private OutboxEvent enabledEvent(Work work, Instant at, String traceId) {
        UUID eventId = ids.next();
        UUID identityId = jdbc.queryForObject("SELECT identity_id FROM memberships WHERE tenant_id = ? AND id = ?",
                UUID.class, work.tenantId(), work.membershipId());
        Map<String, Object> data = Map.of("tenantId", work.tenantId().toString(),
                "membershipId", work.membershipId().toString(), "identityId", identityId.toString(),
                "actorIdentityId", work.actorIdentityId().toString(), "fenceReleasePending", true);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("specversion", "1.0");
        envelope.put("id", eventId.toString());
        envelope.put("source", "urn:saas.forge:tenant-access-service");
        envelope.put("type", "com.saas.forge.membership.enabled.v1");
        envelope.put("subject", work.membershipId().toString());
        envelope.put("time", at.toString());
        envelope.put("datacontenttype", "application/json");
        envelope.put("dataschema", "https://saas.forge.io/contracts/events/membership-enabled.v1.schema.json");
        if (traceId != null) envelope.put("traceId", traceId);
        envelope.put("data", data);
        return new OutboxEvent(eventId, work.tenantId(), at, topic, work.tenantId().toString(),
                traceId, json.writeValueAsString(envelope));
    }

    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MILLIS); }
    private static OffsetDateTime sql(Instant value) { return OffsetDateTime.ofInstant(value, ZoneOffset.UTC); }
    private static void requireV7(UUID id) {
        if (id == null || id.version() != 7) throw new MembershipEnableFailure("INVALID_UUID_V7");
    }

    private record Disable(UUID id, UUID revocationId, String status) {}
    private record Work(UUID operationId, UUID tenantId, UUID membershipId, UUID actorMembershipId,
            UUID actorIdentityId, UUID disableId, UUID revocationId, UUID consumeId,
            UUID compensationId, UUID fenceReleaseId, String state) {}
    public record Progress(UUID operationId, String operationStatus, String membershipStatus, boolean pending) {}
}
