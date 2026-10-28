package io.saasforge.iam.role.service;

import io.saasforge.iam.membership.model.RoleGrants;
import io.saasforge.iam.membership.service.RoleGrantSource;
import io.saasforge.iam.permission.service.PermissionService;
import io.saasforge.iam.role.mapper.RoleDataPolicyMapper;
import io.saasforge.iam.role.mapper.RoleMapper;
import io.saasforge.iam.role.mapper.RolePermissionMapper;
import io.saasforge.iam.role.model.DataPolicy;
import io.saasforge.iam.role.model.OwnerContact;
import io.saasforge.sdk.abac.DataScope;
import io.saasforge.sdk.core.ResourceManifest.RoleTemplate;
import io.saasforge.sdk.core.UuidV7;
import io.saasforge.sdk.core.api.ForgeException;
import io.saasforge.sdk.core.api.FrameworkErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 租户角色与 Membership 的权限明细。 */
@Service
public class RoleService implements RoleGrantSource {
    /** Owner：对租户负最终责任，每个租户唯一的持有者，不可删除。 */
    public static final String OWNER = "owner";

    /** Tenant Admin：拥有全部有效 Permission Code，不可删除。 */
    public static final String TENANT_ADMIN = "tenant-admin";

    // 预置角色的权限不落授权记录而在发布时取全部有效权限码，业务服务新增权限码后无需为每个租户补授权。
    // TODO(#20): 套餐接入后，取值改为 Plan 范围内的有效权限码。
    static final Set<String> ALL_PERMISSIONS = Set.of(OWNER, TENANT_ADMIN);

    private final RoleMapper roles;
    private final RolePermissionMapper grants;
    private final RoleDataPolicyMapper dataPolicies;
    private final PermissionService permissions;

    RoleService(
            RoleMapper roles,
            RolePermissionMapper grants,
            RoleDataPolicyMapper dataPolicies,
            PermissionService permissions) {
        this.roles = roles;
        this.grants = grants;
        this.dataPolicies = dataPolicies;
        this.permissions = permissions;
    }

    /** 为租户预置 Owner 与 Tenant Admin，可重复调用。新租户开通时调用（工单 14）；已有租户由迁移补齐。 */
    @Transactional
    public void provisionBuiltinRoles(UUID tenantId) {
        roles.insertBuiltin(UuidV7.generate(), tenantId, OWNER, "所有者");
        roles.insertBuiltin(UuidV7.generate(), tenantId, TENANT_ADMIN, "租户管理员");
    }

    /**
     * 按各业务服务当前清单中的角色模板为租户预置自定义角色，可重复调用：已有同编码角色时跳过，不覆盖租户的修改。
     * 只在开通时调用，之后新增的模板不会补到已有租户。
     */
    @Transactional
    public void provisionTemplateRoles(UUID tenantId) {
        for (RoleTemplate template : permissions.currentRoleTemplates()) {
            UUID id = UuidV7.generate();
            if (roles.insertCustom(tenantId, id, template.code(), template.name(), null) == 1
                    && !template.permissions().isEmpty()) {
                grants.grant(id, template.permissions());
            }
        }
    }

    /** 授予租户的 Owner 角色，可重复调用。须先预置角色。 */
    public void grantOwner(UUID tenantId, UUID membershipId) {
        roles.grantOwner(tenantId, membershipId);
    }

    /**
     * 在事务内取得租户的角色写锁，至事务结束。成员管理在改变成员状态、转让 Owner 前调用，与角色分配串行；
     * 须先于任何 Membership 行锁取得，与角色分配的加锁顺序一致，避免死锁。
     */
    public void lockTenant(UUID tenantId) {
        roles.lockTenant(tenantId);
    }

    /** 租户当前的 Owner Membership；尚未开通完成时为空。 */
    public Optional<UUID> ownerOf(UUID tenantId) {
        return Optional.ofNullable(roles.ownerOf(tenantId));
    }

    /** 撤销 Membership 的全部角色（Owner 除外，Owner 只能经转让变更）。成员离开租户时在其事务内调用。 */
    public void revokeAll(UUID membershipId) {
        roles.revokeExcept(membershipId, List.of());
    }

    /**
     * 把 Owner 从 {@code from} 转给 {@code to}，原 Owner 降为 Tenant Admin。须在持有 {@link #lockTenant} 的事务内调用：
     * 撤销与授予之间不会有其他转让插入，租户始终恰有一个 Owner。
     *
     * @return {@code from} 不是 Owner 时为 false，不做任何修改
     */
    public boolean transferOwner(UUID tenantId, UUID from, UUID to) {
        if (roles.revokeOwner(tenantId, from) == 0) return false;
        // 唯一索引保证至多一个持有者；刚撤销了唯一的持有者，这里一定插入成功。
        roles.grantOwner(tenantId, to);
        roles.grantTenantAdmin(tenantId, from);
        return true;
    }

    /** 租户 Owner 的联系目标（邮箱优先，否则手机号），用于到期提醒等发给 Owner 的通知。 */
    public List<String> ownerContacts(UUID tenantId) {
        return roles.ownerContacts(tenantId).stream().map(OwnerContact::target).toList();
    }

    /**
     * 要求 Membership 当前拥有 Permission Code，用于 iam 自身的租户管理操作。按数据库中的当前角色判断，
     * 与 {@code @RequiresPermission} 读取的 Redis 明细在授权变更后的下一次请求起一致。
     *
     * @throws ForgeException 403 {@code auth.forbidden}
     */
    public void requirePermission(UUID membershipId, String code) {
        if (!grantsOf(membershipId).permissions().contains(code)) {
            throw new ForgeException(FrameworkErrorCode.AUTH_FORBIDDEN);
        }
    }

    /**
     * Membership 当前的角色、权限与 Data Policy：持有预置角色时拥有全部有效权限码，在全部 Data Resource 上为「全部」；
     * 否则为各角色有效授权的并集与各角色的策略。只发布当前清单中仍声明的资源上的策略。
     */
    @Override
    public RoleGrants grantsOf(UUID membershipId) {
        List<String> codes = roles.roleCodes(membershipId);
        boolean all = codes.stream().anyMatch(ALL_PERMISSIONS::contains);
        Set<String> resources = permissions.currentDataResources().stream()
                .map(current -> current.resource().code())
                .collect(Collectors.toSet());
        Map<String, List<String>> policies = new TreeMap<>();
        if (all) {
            resources.forEach(resource -> policies.put(resource, List.of(DataScope.ALL.template())));
        } else {
            for (DataPolicy policy : dataPolicies.policiesOf(membershipId)) {
                if (!resources.contains(policy.resource())) continue;
                policies.computeIfAbsent(policy.resource(), key -> new ArrayList<>())
                        .add(scopeOf(policy.scope()).template());
            }
        }
        return new RoleGrants(
                Set.copyOf(codes),
                Set.copyOf(all ? permissions.activeCodes() : grants.grantedCodes(membershipId)),
                policies);
    }

    /** 存储与接口中的范围名到预设，如 {@code department_and_below}；未知名称抛出 {@link IllegalArgumentException}。 */
    static DataScope scopeOf(String scope) {
        return DataScope.valueOf(scope.toUpperCase(Locale.ROOT));
    }

    /** 预设在存储与接口中的名称。 */
    static String scopeName(DataScope scope) {
        return scope.name().toLowerCase(Locale.ROOT);
    }
}
