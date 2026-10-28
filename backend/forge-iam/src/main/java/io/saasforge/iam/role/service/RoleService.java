package io.saasforge.iam.role.service;

import io.saasforge.iam.permission.service.PermissionService;
import io.saasforge.iam.role.mapper.RoleMapper;
import io.saasforge.iam.role.model.OwnerContact;
import io.saasforge.sdk.core.UuidV7;
import io.saasforge.sdk.core.api.ForgeException;
import io.saasforge.sdk.core.api.FrameworkErrorCode;
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

    /** 授予租户的 Owner 角色，可重复调用。须先预置角色。 */
    public void grantOwner(UUID tenantId, UUID membershipId) {
        roles.grantOwner(tenantId, membershipId);
    }

    /** 租户 Owner 的联系目标（邮箱优先，否则手机号），用于到期提醒等发给 Owner 的通知。 */
    public List<String> ownerContacts(UUID tenantId) {
        return roles.ownerContacts(tenantId).stream().map(OwnerContact::target).toList();
    }

    /**
     * 要求 Membership 持有 Owner 或 Tenant Admin，用于成员与组织等租户管理操作。
     *
     * @throws ForgeException 403 {@code auth.forbidden}
     */
    // TODO(#16): 自定义角色接入后，改为按 iam 自身的管理类 Permission Code 判断。
    public void requireTenantAdmin(UUID membershipId) {
        if (roles.roleCodes(membershipId).stream().noneMatch(ALL_PERMISSIONS::contains)) {
            throw new ForgeException(FrameworkErrorCode.AUTH_FORBIDDEN);
        }
    }

    /** Membership 当前的角色与权限：持有预置角色时拥有全部有效权限码。 */
    public Grants grantsOf(UUID membershipId) {
        List<String> codes = roles.roleCodes(membershipId);
        // TODO(#16): 自定义角色接入后，并入其授权的有效权限码。
        boolean all = codes.stream().anyMatch(ALL_PERMISSIONS::contains);
        return new Grants(Set.copyOf(codes), all ? Set.copyOf(permissions.activeCodes()) : Set.of());
    }

    /**
     * @param roles 角色编码
     * @param permissions 有效的 Permission Code
     */
    public record Grants(Set<String> roles, Set<String> permissions) {}
}
