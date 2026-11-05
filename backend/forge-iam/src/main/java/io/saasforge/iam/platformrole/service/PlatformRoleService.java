package io.saasforge.iam.platformrole.service;

import io.saasforge.iam.operator.model.OperatorGrants;
import io.saasforge.iam.operator.service.OperatorGrantSource;
import io.saasforge.iam.operator.service.OperatorService;
import io.saasforge.iam.permission.model.PermissionRow;
import io.saasforge.iam.permission.service.PermissionService;
import io.saasforge.iam.platformrole.mapper.PlatformRoleMapper;
import io.saasforge.iam.platformrole.model.OperatorRoleRow;
import io.saasforge.iam.role.model.DataPolicy;
import io.saasforge.sdk.abac.DataScope;
import io.saasforge.sdk.core.api.ForgeException;
import io.saasforge.sdk.core.api.FrameworkErrorCode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Operator 的平台角色明细与权限判断。规则与租户侧的 {@code RoleService} 相同，数据来自平台角色的独立表，
 * 权限码与数据资源只取 Platform Realm 服务申报的。
 */
@Service
public class PlatformRoleService implements OperatorGrantSource {
    /** 超级管理员：拥有全部有效的平台权限码，在全部平台数据资源上为「全部」，不可修改或删除。 */
    public static final String SUPER_ADMIN = "super-admin";

    private final PlatformRoleMapper roles;
    private final PermissionService permissions;
    private final OperatorService operators;

    PlatformRoleService(PlatformRoleMapper roles, PermissionService permissions, OperatorService operators) {
        this.roles = roles;
        this.permissions = permissions;
        this.operators = operators;
    }

    /**
     * Operator 当前的平台角色、权限与 Data Policy：持有超级管理员时拥有全部有效平台权限码，在全部平台数据资源上为「全部」；
     * 否则为各角色有效授权的并集与各角色的策略（预设展开为模板，自定义条件原样发布）。只发布当前清单中仍声明的资源上的策略。
     */
    @Override
    public OperatorGrants grantsOf(UUID operatorId) {
        List<String> codes = roles.roleCodes(operatorId);
        boolean all = codes.contains(SUPER_ADMIN);
        Set<String> resources = permissions.currentPlatformDataResources().stream()
                .map(current -> current.resource().code())
                .collect(Collectors.toSet());
        Map<String, List<String>> policies = new TreeMap<>();
        if (all) {
            resources.forEach(resource -> policies.put(resource, List.of(DataScope.ALL.template())));
        } else {
            for (DataPolicy policy : roles.policiesOf(operatorId)) {
                if (!resources.contains(policy.resource())) continue;
                policies.computeIfAbsent(policy.resource(), key -> new ArrayList<>())
                        .add(
                                DataPolicy.CUSTOM.equals(policy.scope())
                                        ? policy.condition()
                                        : DataScope.valueOf(policy.scope().toUpperCase(Locale.ROOT))
                                                .template());
            }
        }
        Set<String> effective = all
                ? permissions.activePlatformPermissions().stream()
                        .map(PermissionRow::code)
                        .collect(Collectors.toSet())
                : Set.copyOf(roles.grantedCodes(operatorId));
        return new OperatorGrants(Set.copyOf(codes), effective, policies);
    }

    /** 当前 Operator 的平台权限，供界面展示；已停用或删除的 Operator 没有任何权限。 */
    public OperatorGrants accessOf(UUID operatorId) {
        if (operators.findActive(operatorId) == null) return new OperatorGrants(Set.of(), Set.of(), Map.of());
        return grantsOf(operatorId);
    }

    /**
     * 要求操作人是超级管理员，当目标 Operator 持有超级管理员或将被分配超级管理员时调用：否则持有
     * {@code platform:operator:manage} 的 Operator 可以给自己分配超级管理员，或重置超级管理员的密码后登录其账号，等同提权。
     *
     * @param target 被操作的 Operator，新建时为 null
     * @param roleIds 将分配的平台角色，不分配时为空
     * @throws ForgeException 403 {@code auth.forbidden}
     */
    public void requireSuperAdminFor(UUID actor, UUID target, Collection<UUID> roleIds) {
        boolean touchesSuperAdmin = (target != null && roles.roleCodes(target).contains(SUPER_ADMIN))
                || roles.list().stream().anyMatch(role -> role.builtin() && roleIds.contains(role.id()));
        if (touchesSuperAdmin && !roles.roleCodes(actor).contains(SUPER_ADMIN)) {
            throw new ForgeException(FrameworkErrorCode.AUTH_FORBIDDEN);
        }
    }

    /**
     * 要求操作人是 active 且持有超级管理员的 Operator，用于只有超级管理员能做、不另设权限码的操作（如重置 Operator 的 MFA）。
     *
     * @throws ForgeException 403 {@code auth.forbidden}
     */
    public void requireSuperAdmin(UUID actor) {
        if (operators.findActive(actor) == null || !roles.roleCodes(actor).contains(SUPER_ADMIN)) {
            throw new ForgeException(FrameworkErrorCode.AUTH_FORBIDDEN);
        }
    }

    /** 在事务内取得平台角色的写锁，至事务结束：授权、分配与 Operator 停用串行，「至少保留一位超级管理员」的判断才可靠。 */
    public void lock() {
        roles.lock();
    }

    /** 全部未删除 Operator 的平台角色，按 Operator 分组。 */
    public Map<UUID, List<UUID>> assignments() {
        Map<UUID, List<UUID>> rolesOf = new HashMap<>();
        for (OperatorRoleRow row : roles.assignments()) {
            rolesOf.computeIfAbsent(row.operatorId(), key -> new ArrayList<>()).add(row.roleId());
        }
        return rolesOf;
    }

    /**
     * 整体替换 Operator 的平台角色，可多选，权限取并集。须在持有 {@link #lock()} 的事务内调用；版本由调用方在提交后递增。
     *
     * @return 分配是否发生变化
     * @throws ForgeException 400 {@code platformRole.invalidAssignment}；409 {@code platformRole.lastSuperAdmin}
     */
    public boolean assign(UUID operatorId, Collection<UUID> roleIds) {
        Set<UUID> assigned = new LinkedHashSet<>(roleIds);
        if (!assigned.isEmpty() && roles.countRoles(assigned) != assigned.size()) {
            throw new ForgeException(PlatformRoleErrorCode.INVALID_ASSIGNMENT);
        }
        int rows = roles.revokeExcept(operatorId, assigned);
        if (!assigned.isEmpty()) rows += roles.assign(operatorId, assigned);
        // 先改后查：同一事务内能看到本次修改，撤销的是最后一位超级管理员时整体回滚。
        if (rows > 0) requireSuperAdminRemains();
        return rows > 0;
    }

    /**
     * 要求仍有至少一位持有超级管理员、状态正常的 Operator，在停用或撤销角色之后于同一事务内调用。
     *
     * @throws ForgeException 409 {@code platformRole.lastSuperAdmin}
     */
    public void requireSuperAdminRemains() {
        if (roles.countActiveSuperAdmins() == 0) throw new ForgeException(PlatformRoleErrorCode.LAST_SUPER_ADMIN);
    }

    /**
     * 要求 Operator 当前为 active 且拥有任一给定的平台 Permission Code，用于 iam 自身的平台管理操作。按数据库中的当前状态判断，
     * 停用与授权变更从下一次请求起生效，不依赖 Token 刷新。
     *
     * @throws ForgeException 403 {@code auth.forbidden}
     */
    public void requirePermission(UUID operatorId, String... codes) {
        if (operators.findActive(operatorId) == null) throw new ForgeException(FrameworkErrorCode.AUTH_FORBIDDEN);
        Set<String> granted = grantsOf(operatorId).permissions();
        for (String code : codes) {
            if (granted.contains(code)) return;
        }
        throw new ForgeException(FrameworkErrorCode.AUTH_FORBIDDEN);
    }
}
