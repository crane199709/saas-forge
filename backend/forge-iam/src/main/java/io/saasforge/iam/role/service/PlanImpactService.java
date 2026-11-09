package io.saasforge.iam.role.service;

import io.saasforge.iam.membership.service.MembershipService;
import io.saasforge.iam.permission.model.PermissionRow;
import io.saasforge.iam.permission.service.PermissionService;
import io.saasforge.iam.role.mapper.PlanImpactMapper;
import io.saasforge.iam.role.model.MembershipCodeRow;
import io.saasforge.iam.role.model.PlanImpactResponse;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** 统计 Plan 变更对租户成员的影响，只读。失去哪些 Permission Code 与 Sub-App 由 platform 按 Plan 主数据算出后传入。 */
@Service
public class PlanImpactService {
    private final RoleService roles;
    private final MembershipService memberships;
    private final PermissionService permissions;
    private final PlanImpactMapper impacts;

    PlanImpactService(
            RoleService roles, MembershipService memberships, PermissionService permissions, PlanImpactMapper impacts) {
        this.roles = roles;
        this.memberships = memberships;
        this.permissions = permissions;
        this.impacts = impacts;
    }

    public PlanImpactResponse impact(
            UUID tenantId, Collection<String> lostPermissions, Collection<String> lostSubApps) {
        return new PlanImpactResponse(memberships.count(tenantId), affected(tenantId, lostPermissions, lostSubApps));
    }

    private long affected(UUID tenantId, Collection<String> lostPermissions, Collection<String> lostSubApps) {
        if (lostPermissions.isEmpty() && lostSubApps.isEmpty()) return 0;
        Predicate<Set<String>> seesLostMenu = permissions.seesMenuIn(lostSubApps);
        // 权限越多可见的菜单越多：没有任何权限也能看到时，每个正常成员都会失去，无需逐人判断。
        if (seesLostMenu.test(Set.of())) return memberships.countActive(tenantId);
        Set<String> grantable = roles.grantablePermissions(tenantId).stream()
                .map(PermissionRow::code)
                .collect(Collectors.toSet());
        Map<UUID, List<String>> granted = byMembership(impacts.activeGrantedCodes(tenantId));
        // 没有角色的成员有效权限为空，上面已判定看不到失去的菜单，也不持有失去的权限码，只需遍历持有角色的成员。
        return byMembership(impacts.activeRoleCodes(tenantId)).entrySet().stream()
                .filter(member -> {
                    // 当前已不生效的授权（超出现有 Plan 或已下线）本来就用不了，不算失去。
                    Set<String> effective = RoleService.effectivePermissions(
                            member.getValue(), granted.getOrDefault(member.getKey(), List.of()), grantable);
                    return !Collections.disjoint(effective, lostPermissions) || seesLostMenu.test(effective);
                })
                .count();
    }

    private static Map<UUID, List<String>> byMembership(List<MembershipCodeRow> rows) {
        return rows.stream()
                .collect(Collectors.groupingBy(
                        MembershipCodeRow::membershipId,
                        Collectors.mapping(MembershipCodeRow::code, Collectors.toList())));
    }
}
