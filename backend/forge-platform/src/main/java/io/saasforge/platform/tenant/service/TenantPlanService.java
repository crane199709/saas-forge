package io.saasforge.platform.tenant.service;

import io.saasforge.platform.plan.entity.Plan;
import io.saasforge.platform.plan.service.PlanUsage;
import io.saasforge.platform.tenant.entity.Tenant;
import io.saasforge.platform.tenant.mapper.TenantMapper;
import io.saasforge.sdk.core.CurrentActorProvider;
import io.saasforge.sdk.core.CurrentTenant;
import io.saasforge.sdk.core.api.ForgeException;
import io.saasforge.sdk.mq.EventPublisher;
import io.saasforge.sdk.provisioning.TenantCreated;
import io.saasforge.sdk.provisioning.TenantPlanChanged;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 租户与 Plan 的分配：更换 Plan 或 Plan 的内容被修改时递增租户的 Plan 版本，并在同一事务中发布 {@link TenantPlanChanged}。
 *
 * <p>与 {@link TenantService} 分开：Plan 功能包经 {@link PlanUsage} 回调本类，而 {@link TenantService} 又要经
 * Plan 功能包读取 Plan，合在一起会形成 Bean 循环依赖。
 */
@Service
class TenantPlanService implements PlanUsage {
    private final TenantMapper tenants;
    private final EventPublisher events;
    private final CurrentActorProvider actors;

    TenantPlanService(TenantMapper tenants, EventPublisher events, CurrentActorProvider actors) {
        this.tenants = tenants;
        this.events = events;
        this.actors = actors;
    }

    /** 为租户分配 Plan 并广播。调用方须已经 {@code PlanService#lockForAssignment} 锁定该 Plan。 */
    void assign(UUID tenantId, Plan plan) {
        Long version =
                tenants.assignPlan(tenantId, plan.getId(), actors.currentActor().orElse(null));
        if (version == null) {
            throw ForgeException.withErrorMessage(TenantErrorCode.NOT_FOUND, "Tenant " + tenantId + " not found");
        }
        publish(tenantId, plan, version);
    }

    /** 新建租户时广播初始 Plan；租户行已带 Plan 与版本写入。 */
    void publish(UUID tenantId, Plan plan, long version) {
        var event = new TenantPlanChanged(
                tenantId,
                plan.getId(),
                plan.getCode(),
                List.of(plan.getPermissions()),
                List.of(plan.getSubApps()),
                plan.getMemberQuota(),
                plan.getStorageQuotaMb(),
                version);
        // 带该租户的上下文发布，理由同 TenantService
        CurrentTenant.run(tenantId, () -> events.publish(TenantCreated.TOPIC, event));
    }

    // 冷静期结束、数据已清理的租户不再需要 Plan，不阻止删除。
    @Override
    public boolean inUse(UUID planId) {
        return tenants.countUsingPlan(planId) > 0;
    }

    @Override
    public void planChanged(Plan plan) {
        for (Tenant tenant :
                tenants.touchPlan(plan.getId(), actors.currentActor().orElse(null))) {
            publish(tenant.getId(), plan, tenant.getPlanVersion());
        }
    }
}
