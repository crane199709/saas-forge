package io.saasforge.iam.role.service;

import io.saasforge.iam.membership.service.MembershipService;
import io.saasforge.iam.permission.model.PermissionRow;
import io.saasforge.iam.role.mapper.PlanImpactMapper;
import io.saasforge.iam.role.model.PlanImpactResponse;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 统计 Plan 变更对租户成员的影响，只读。失去哪些 Permission Code 与 Sub-App 由 platform 按 Plan 主数据算出后传入。 */
@Service
public class PlanImpactService {
    private final RoleService roles;
    private final MembershipService memberships;
    private final PlanImpactMapper impacts;

    PlanImpactService(RoleService roles, MembershipService memberships, PlanImpactMapper impacts) {
        this.roles = roles;
        this.memberships = memberships;
        this.impacts = impacts;
    }

    public PlanImpactResponse impact(
            UUID tenantId, Collection<String> lostPermissions, Collection<String> lostSubApps) {
        return new PlanImpactResponse(memberships.count(tenantId), affected(tenantId, lostPermissions, lostSubApps));
    }

    private long affected(UUID tenantId, Collection<String> lostPermissions, Collection<String> lostSubApps) {
        // Sub-App 由 Feature Pack 对整个租户开放，不按成员授权，失去时全部正常成员都受影响。
        if (!lostSubApps.isEmpty()) return impacts.countActive(tenantId);
        // 当前已不生效的授权（超出现有 Plan 或已下线）本来就用不了，不算失去。
        Set<String> lost = Set.copyOf(lostPermissions);
        List<String> effective = roles.grantablePermissions(tenantId).stream()
                .map(PermissionRow::code)
                .filter(lost::contains)
                .toList();
        if (effective.isEmpty()) return 0;
        return impacts.countHolders(tenantId, effective, RoleService.ALL_PERMISSIONS);
    }
}
