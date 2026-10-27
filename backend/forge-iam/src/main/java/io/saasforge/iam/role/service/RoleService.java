package io.saasforge.iam.role.service;

import io.saasforge.iam.permission.service.PermissionService;
import io.saasforge.iam.role.mapper.RoleMapper;
import io.saasforge.sdk.core.MembershipGrants;
import io.saasforge.sdk.core.UuidV7;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 租户角色与 Membership 的权限明细。 */
@Service
public class RoleService {
    /** Owner：对租户负最终责任，每个租户唯一的持有者，不可删除。 */
    public static final String OWNER = "owner";

    /** Tenant Admin：拥有全部有效 Permission Code，不可删除。 */
    public static final String TENANT_ADMIN = "tenant-admin";

    // 预置角色的权限不落授权记录而在发布时取全部有效权限码，业务服务新增权限码后无需为每个租户补授权。
    // TODO(#20): 套餐接入后，取值改为 Plan 范围内的有效权限码。
    private static final Set<String> ALL_PERMISSIONS = Set.of(OWNER, TENANT_ADMIN);

    private final RoleMapper roles;
    private final PermissionService permissions;

    RoleService(RoleMapper roles, PermissionService permissions) {
        this.roles = roles;
        this.permissions = permissions;
    }

    /** 为租户预置 Owner 与 Tenant Admin，可重复调用。新租户开通时调用（工单 14）；已有租户由迁移补齐。 */
    @Transactional
    public void provisionBuiltinRoles(UUID tenantId) {
        roles.insertBuiltin(UuidV7.generate(), tenantId, OWNER, "所有者");
        roles.insertBuiltin(UuidV7.generate(), tenantId, TENANT_ADMIN, "租户管理员");
    }

    /** Membership 当前的角色与权限：持有预置角色时拥有全部有效权限码。 */
    public MembershipGrants grantsOf(UUID membershipId) {
        List<String> codes = roles.roleCodes(membershipId);
        // TODO(#16): 自定义角色接入后，并入其授权的有效权限码。
        boolean all = codes.stream().anyMatch(ALL_PERMISSIONS::contains);
        return new MembershipGrants(Set.copyOf(codes), all ? Set.copyOf(permissions.activeCodes()) : Set.of());
    }
}
