package io.saasforge.platform.tenant.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import io.saasforge.platform.tenant.config.LifecycleProperties;
import io.saasforge.platform.tenant.entity.Tenant;
import io.saasforge.platform.tenant.entity.TenantStatusChange;
import io.saasforge.platform.tenant.mapper.TenantMapper;
import io.saasforge.platform.tenant.mapper.TenantStatusChangeMapper;
import io.saasforge.sdk.core.CurrentTenant;
import io.saasforge.sdk.core.api.ForgeException;
import io.saasforge.sdk.mq.EventPublisher;
import io.saasforge.sdk.provisioning.TenantCreated;
import io.saasforge.sdk.provisioning.TenantDeleted;
import io.saasforge.sdk.provisioning.TenantExpiryApproached;
import io.saasforge.sdk.provisioning.TenantStatusChanged;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tenant Lifecycle 状态机（规格 5.3）：按合法迁移表变更状态并广播，删除进入 Cooling-off Period，到期提醒。
 *
 * <p>状态变更、历史记录与 {@link TenantStatusChanged} 同一事务提交；状态版本作为乐观锁，并发变更只有一个成功。
 */
@Service
public class TenantLifecycleService {
    public static final String PENDING = "pending";
    public static final String TRIAL = "trial";
    public static final String ACTIVE = "active";
    public static final String SUSPENDED = "suspended";
    public static final String EXPIRED = "expired";
    public static final String ARCHIVED = "archived";
    public static final String DELETED = "deleted";

    /**
     * 合法迁移表：键为当前状态，值为可迁移到的状态。deleted 不在表中：冷静期内只能撤回到删除前的状态，冷静期结束后不能再迁移。
     */
    static final Map<String, List<String>> TRANSITIONS = Map.of(
            PENDING, List.of(TRIAL, ACTIVE, DELETED),
            TRIAL, List.of(ACTIVE, SUSPENDED, EXPIRED, DELETED),
            ACTIVE, List.of(SUSPENDED, EXPIRED, DELETED),
            SUSPENDED, List.of(TRIAL, ACTIVE, ARCHIVED, DELETED),
            EXPIRED, List.of(ACTIVE, ARCHIVED, DELETED),
            ARCHIVED, List.of(DELETED));

    private static final Logger log = LoggerFactory.getLogger(TenantLifecycleService.class);

    private final TenantService tenantService;
    private final TenantMapper tenants;
    private final TenantStatusChangeMapper changes;
    private final EventPublisher events;
    private final LifecycleProperties properties;
    private final TransactionTemplate transaction;

    TenantLifecycleService(
            TenantService tenantService,
            TenantMapper tenants,
            TenantStatusChangeMapper changes,
            EventPublisher events,
            LifecycleProperties properties,
            TransactionTemplate transaction) {
        this.tenantService = tenantService;
        this.tenants = tenants;
        this.changes = changes;
        this.events = events;
        this.properties = properties;
        this.transaction = transaction;
    }

    /** 租户当前可迁移到的状态；冷静期内只有删除前的状态（撤回删除），冷静期结束后为空。 */
    public List<String> allowedTargets(Tenant tenant) {
        if (DELETED.equals(tenant.getStatus())) {
            boolean revocable =
                    tenant.getPurgedAt() == null && tenant.getPurgeAt().isAfter(OffsetDateTime.now());
            return revocable ? List.of(tenant.getStatusBeforeDeletion()) : List.of();
        }
        return TRANSITIONS.getOrDefault(tenant.getStatus(), List.of());
    }

    /**
     * 把租户迁移到目标状态并发布 {@link TenantStatusChanged}。迁移到 deleted 时进入冷静期；冷静期内迁移回删除前的状态即撤回删除。
     *
     * @param expiresAt 新的试用或订购到期时间，只在迁移到 trial 或 active 时可填，为 null 时保留原值
     * @param reason 变更原因，记入历史
     * @throws ForgeException 409 {@code tenant.illegalTransition} 不在合法迁移表中；409 {@code tenant.statusConflict}
     *     与并发的变更冲突；400 {@code tenant.invalidExpiry} 到期时间不适用或已过去；403 {@code auth.forbidden} 迁移后的租户
     *     不在当前 Operator 的数据范围内
     */
    @Transactional
    public Tenant transition(UUID tenantId, String target, OffsetDateTime expiresAt, String reason) {
        Tenant tenant = tenantService.get(tenantId);
        String from = tenant.getStatus();
        if (!allowedTargets(tenant).contains(target)) {
            throw ForgeException.withErrorMessage(
                    TenantErrorCode.ILLEGAL_TRANSITION,
                    "Tenant " + tenantId + " cannot change from " + from + " to " + target);
        }
        OffsetDateTime now = OffsetDateTime.now();
        if (expiresAt != null && (!(TRIAL.equals(target) || ACTIVE.equals(target)) || !expiresAt.isAfter(now))) {
            throw ForgeException.withErrorMessage(
                    TenantErrorCode.INVALID_EXPIRY, "Expiry " + expiresAt + " does not apply to " + target);
        }
        // 读取时已按迁移前的状态过滤；再按迁移后的状态判断，否则按状态限定范围的 Operator 能把租户改到自己的范围外
        tenantService.requireInScope(tenant.getCreatedBy(), tenant.getRegion(), target);
        long version = tenant.getStatusVersion() + 1;
        var update = new LambdaUpdateWrapper<Tenant>()
                .eq(Tenant::getId, tenantId)
                .eq(Tenant::getStatusVersion, tenant.getStatusVersion())
                // 与冷静期到期的清理互斥：清理已发布 TenantDeleted 后不能再撤回
                .isNull(Tenant::getPurgedAt)
                .set(Tenant::getStatus, target)
                .set(Tenant::getStatusVersion, version);
        if (DELETED.equals(target)) {
            update.set(Tenant::getStatusBeforeDeletion, from)
                    .set(Tenant::getPurgeAt, now.plus(properties.coolingOffPeriod()));
        } else if (DELETED.equals(from)) {
            update.set(Tenant::getStatusBeforeDeletion, null).set(Tenant::getPurgeAt, null);
        }
        if (expiresAt != null) {
            update.set(Tenant::getExpiresAt, expiresAt).set(Tenant::getExpiryRemindedAt, null);
        }
        // 传入空实体以触发标准列填充（updated_at、updated_by），其余列由 set 写入
        if (tenants.update(new Tenant(), update) == 0) {
            throw ForgeException.withErrorMessage(
                    TenantErrorCode.STATUS_CONFLICT, "Tenant " + tenantId + " was changed concurrently");
        }
        tenantService.recordStatusChange(tenantId, from, target, reason);
        publish(tenantId, new TenantStatusChanged(tenantId, tenant.getName(), from, target, version));
        return tenantService.get(tenantId);
    }

    /** 状态变更历史，最近的在前。 */
    public List<TenantStatusChange> history(UUID tenantId) {
        return changes.selectList(new LambdaQueryWrapper<TenantStatusChange>()
                .eq(TenantStatusChange::getTenantId, tenantId)
                .orderByDesc(TenantStatusChange::getChangedAt)
                .orderByDesc(TenantStatusChange::getId));
    }

    /** 为冷静期已结束的租户发布 {@link TenantDeleted}，每个租户只发布一次。 */
    public void purgeDue() {
        List<Tenant> due = tenants.selectList(new LambdaQueryWrapper<Tenant>()
                .eq(Tenant::getStatus, DELETED)
                .isNull(Tenant::getPurgedAt)
                .le(Tenant::getPurgeAt, OffsetDateTime.now()));
        for (Tenant tenant : due) {
            inOwnTransaction(tenant, () -> {
                // 状态版本不变才清理：冷静期刚结束时并发的撤回已提交，就不再删除
                int updated = tenants.update(
                        new Tenant(),
                        new LambdaUpdateWrapper<Tenant>()
                                .eq(Tenant::getId, tenant.getId())
                                .eq(Tenant::getStatusVersion, tenant.getStatusVersion())
                                .isNull(Tenant::getPurgedAt)
                                .set(Tenant::getPurgedAt, OffsetDateTime.now()));
                if (updated == 1) publish(tenant.getId(), new TenantDeleted(tenant.getId()));
            });
        }
    }

    /** 为提醒窗口内即将到期的试用或正常租户发布 {@link TenantExpiryApproached}，每个到期时间只提醒一次。 */
    public void remindDue() {
        OffsetDateTime now = OffsetDateTime.now();
        List<Tenant> due = tenants.selectList(new LambdaQueryWrapper<Tenant>()
                .in(Tenant::getStatus, TRIAL, ACTIVE)
                .isNull(Tenant::getExpiryRemindedAt)
                .gt(Tenant::getExpiresAt, now)
                .le(Tenant::getExpiresAt, now.plus(properties.reminderLeadTime())));
        for (Tenant tenant : due) {
            inOwnTransaction(tenant, () -> {
                int updated = tenants.update(
                        new Tenant(),
                        new LambdaUpdateWrapper<Tenant>()
                                .eq(Tenant::getId, tenant.getId())
                                .eq(Tenant::getStatusVersion, tenant.getStatusVersion())
                                .eq(Tenant::getExpiresAt, tenant.getExpiresAt())
                                .isNull(Tenant::getExpiryRemindedAt)
                                .set(Tenant::getExpiryRemindedAt, OffsetDateTime.now()));
                if (updated == 1) {
                    publish(
                            tenant.getId(),
                            new TenantExpiryApproached(
                                    tenant.getId(), tenant.getName(), tenant.getStatus(), tenant.getExpiresAt()));
                }
            });
        }
    }

    // 每个租户单独提交：一个租户失败不影响其他租户，下次扫描再处理。
    private void inOwnTransaction(Tenant tenant, Runnable task) {
        try {
            transaction.executeWithoutResult(status -> task.run());
        } catch (RuntimeException e) {
            log.error("Failed to process lifecycle task of tenant {}", tenant.getId(), e);
        }
    }

    // 事件带该租户的上下文发布：订阅方恢复后其数据访问照常按租户隔离（如 TenantDeleted 的清理）。
    private void publish(UUID tenantId, Object event) {
        CurrentTenant.run(tenantId, () -> events.publish(TenantCreated.TOPIC, event));
    }
}
