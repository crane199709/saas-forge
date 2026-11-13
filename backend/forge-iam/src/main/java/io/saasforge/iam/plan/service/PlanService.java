package io.saasforge.iam.plan.service;

import io.saasforge.iam.plan.mapper.TenantPlanMapper;
import io.saasforge.sdk.provisioning.TenantPlanChanged;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * iam 中租户 Plan 的副本：Plan 主数据属于 platform，iam 只保存限定授权、成员数与可加载 Sub-App 需要的部分（Feature Pack 的权限码与 Sub-App、成员数配额）。
 *
 * <p>没有登记 Plan 的租户（套餐上线前创建的）不受套餐限制。新租户的 {@code TenantCreated} 与 {@code TenantPlanChanged}
 * 可能先后送达，二者之间该租户短暂不受限，此时 Owner 尚未接受邀请，不会有授权生效。
 */
@Service
public class PlanService {
    private final TenantPlanMapper plans;

    PlanService(TenantPlanMapper plans) {
        this.plans = plans;
    }

    /**
     * 应用 platform 广播的 Plan 变更，只应用比已应用版本更新的变更，返回是否应用。并发应用同一租户的变更时，
     * 后到的版本在行锁上等待先到的提交。
     */
    public boolean apply(TenantPlanChanged event) {
        if (plans.apply(event.tenantId(), event.planId(), event.version(), event.memberQuota()) == 0) {
            return false;
        }
        plans.clearPermissions(event.tenantId());
        if (!event.permissions().isEmpty()) {
            plans.insertPermissions(event.tenantId(), event.permissions());
        }
        plans.clearSubApps(event.tenantId());
        if (!event.subApps().isEmpty()) {
            plans.insertSubApps(event.tenantId(), event.subApps());
        }
        return true;
    }

    /** 租户 Feature Pack 中的 Permission Code；未登记 Plan 时为空，表示不受套餐限制。 */
    public Optional<Set<String>> permissionsOf(UUID tenantId) {
        if (!plans.exists(tenantId)) {
            return Optional.empty();
        }
        return Optional.of(Set.copyOf(plans.permissions(tenantId)));
    }

    /** 租户 Feature Pack 中的 Sub-App 编码；未登记 Plan 时为空，表示不受套餐限制。 */
    public Optional<Set<String>> subAppsOf(UUID tenantId) {
        if (!plans.exists(tenantId)) {
            return Optional.empty();
        }
        return Optional.of(Set.copyOf(plans.subApps(tenantId)));
    }

    /** 租户的成员数上限；未登记 Plan 或不限时为空。 */
    public Optional<Integer> memberQuotaOf(UUID tenantId) {
        return Optional.ofNullable(plans.memberQuota(tenantId));
    }
}
