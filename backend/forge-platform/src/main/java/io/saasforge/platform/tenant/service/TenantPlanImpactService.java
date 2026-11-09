package io.saasforge.platform.tenant.service;

import io.saasforge.platform.plan.entity.Plan;
import io.saasforge.platform.plan.model.CatalogPermission;
import io.saasforge.platform.plan.model.TenantMemberImpact;
import io.saasforge.platform.plan.service.PlanService;
import io.saasforge.platform.plan.service.TenantMemberImpactClient;
import io.saasforge.platform.subapp.service.SubAppService;
import io.saasforge.platform.tenant.entity.Tenant;
import io.saasforge.platform.tenant.model.PlanChangeImpactResponse;
import io.saasforge.sdk.core.api.ForgeException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 更换租户 Plan 前预览影响，只读：不改变 Plan、不广播。预览只提示，不拦截后续的更换。 */
@Service
public class TenantPlanImpactService {
    private final TenantService tenants;
    private final PlanService plans;
    private final SubAppService subApps;
    private final TenantMemberImpactClient members;

    TenantPlanImpactService(
            TenantService tenants, PlanService plans, SubAppService subApps, TenantMemberImpactClient members) {
        this.tenants = tenants;
        this.plans = plans;
        this.subApps = subApps;
        this.members = members;
    }

    /**
     * 预览把租户更换为给定 Plan 的影响。租户的可见范围与更换 Plan 相同。
     *
     * @throws ForgeException 404 {@code tenant.notFound} 或 {@code plan.notFound}；503 {@code plan.impactUnavailable}
     *     或 {@code plan.catalogUnavailable} iam 不可用
     */
    public PlanChangeImpactResponse preview(UUID tenantId, UUID planId) {
        Tenant tenant = tenants.get(tenantId);
        Plan target = plans.get(planId);
        if (planId.equals(tenant.getPlanId())) return none(target);
        Plan current = tenant.getPlanId() == null ? null : plans.get(tenant.getPlanId());
        List<String> lostPermissions = lost(permissionsOf(current), target.getPermissions());
        List<String> lostSubApps = lost(subAppsOf(current), target.getSubApps());
        // 升级不依赖 iam：没有可失去的内容时，iam 不可用也不影响给出空结果。
        if (lostPermissions.isEmpty() && lostSubApps.isEmpty() && !quotaLowered(current, target)) return none(target);
        TenantMemberImpact impact = members.impact(tenantId, lostPermissions, lostSubApps);
        Integer quota = target.getMemberQuota();
        return new PlanChangeImpactResponse(
                lostPermissions,
                lostSubApps,
                impact.memberCount(),
                quota,
                quota == null ? 0 : Math.max(0, impact.memberCount() - quota),
                impact.affectedMemberships());
    }

    private static PlanChangeImpactResponse none(Plan target) {
        return new PlanChangeImpactResponse(List.of(), List.of(), null, target.getMemberQuota(), 0, 0);
    }

    // 未分配 Plan 的租户不受套餐限制，当前可用范围是权限码目录与 App Registry 的全部内容，不能按「什么都没有」算成零损失。
    private Collection<String> permissionsOf(Plan plan) {
        if (plan != null) return Arrays.asList(plan.getPermissions());
        return plans.catalog().stream().map(CatalogPermission::code).toList();
    }

    private Collection<String> subAppsOf(Plan plan) {
        if (plan != null) return Arrays.asList(plan.getSubApps());
        return subApps.list().stream().map(details -> details.app().getCode()).toList();
    }

    private static List<String> lost(Collection<String> current, String[] target) {
        var lost = new TreeSet<>(current);
        lost.removeAll(Set.of(target));
        return List.copyOf(lost);
    }

    private static boolean quotaLowered(Plan current, Plan target) {
        if (target.getMemberQuota() == null) return false;
        return current == null
                || current.getMemberQuota() == null
                || target.getMemberQuota() < current.getMemberQuota();
    }
}
