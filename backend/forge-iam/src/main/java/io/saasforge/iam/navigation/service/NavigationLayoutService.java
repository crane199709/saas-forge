package io.saasforge.iam.navigation.service;

import io.saasforge.iam.membership.model.TenantActor;
import io.saasforge.iam.navigation.mapper.NavigationLayoutMapper;
import io.saasforge.iam.navigation.model.NavigationLayout;
import io.saasforge.iam.navigation.model.NavigationLayout.Node;
import io.saasforge.iam.permission.model.PermissionRow;
import io.saasforge.iam.permission.service.IamPermissions;
import io.saasforge.iam.permission.service.PermissionService;
import io.saasforge.iam.permission.service.PlatformPermissions;
import io.saasforge.iam.plan.service.PlanService;
import io.saasforge.iam.platformrole.service.PlatformRoleService;
import io.saasforge.iam.role.service.RoleService;
import io.saasforge.iam.tenant.service.TenantService;
import io.saasforge.sdk.audit.AuditCategory;
import io.saasforge.sdk.audit.AuditEvent;
import io.saasforge.sdk.audit.AuditRealm;
import io.saasforge.sdk.audit.AuditRecorder;
import io.saasforge.sdk.core.ResourceManifest.MenuNode;
import io.saasforge.sdk.core.UuidV7;
import io.saasforge.sdk.core.api.ForgeException;
import io.saasforge.sdk.core.api.FrameworkErrorCode;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * 两端的 Navigation Layout（导航布局规格第 6 节）。布局只决定位置，不决定可见性（ADR-0018）：保存时只校验结构，不校验引用是否存在，
 * 已下线的页面要保留位置。并发保存时后保存的覆盖先保存的。
 */
@Service
public class NavigationLayoutService {
    private static final String REPLACE = "navigationLayout.replace";
    private static final String RESET = "navigationLayout.reset";
    // 与 Shell App 的导航一致，顶层算第一层。
    private static final int MAX_DEPTH = 3;
    private static final int MAX_NODES = 500;
    private static final int MAX_GROUP_ID = 64;
    private static final int MAX_GROUP_NAME = 32;

    private final NavigationLayoutMapper layouts;
    private final TenantService tenants;
    private final RoleService roles;
    private final PlatformRoleService platformRoles;
    private final PermissionService permissions;
    private final PlanService plans;
    private final AuditRecorder audits;
    private final JsonMapper json;

    NavigationLayoutService(
            NavigationLayoutMapper layouts,
            TenantService tenants,
            RoleService roles,
            PlatformRoleService platformRoles,
            PermissionService permissions,
            PlanService plans,
            AuditRecorder audits,
            JsonMapper json) {
        this.layouts = layouts;
        this.tenants = tenants;
        this.roles = roles;
        this.platformRoles = platformRoles;
        this.permissions = permissions;
        this.plans = plans;
        this.audits = audits;
        this.json = json;
    }

    /** 租户的布局，供 {@code /auth/access} 下发给每个成员，不校验权限；没有布局时为 null。 */
    public NavigationLayout tenantLayoutForAccess(UUID tenantId) {
        return read(tenants.navigationLayout(tenantId));
    }

    /** 管理页读取租户的布局，需要 {@link IamPermissions#MENU_MANAGE}；没有布局时为 null。 */
    public NavigationLayout tenantLayout(TenantActor actor) {
        roles.requirePermission(actor.membershipId(), IamPermissions.MENU_MANAGE);
        return tenantLayoutForAccess(actor.tenantId());
    }

    /**
     * 管理页的目录：按 Plan 裁剪、不按操作者本人权限裁剪的清单菜单，范围同角色授权树，去掉页面操作。需要
     * {@link IamPermissions#MENU_MANAGE}。
     */
    public List<MenuNode> tenantCatalog(TenantActor actor) {
        roles.requirePermission(actor.membershipId(), IamPermissions.MENU_MANAGE);
        Set<String> grantable = roles.grantablePermissions(actor.tenantId()).stream()
                .map(PermissionRow::code)
                .collect(Collectors.toSet());
        return PermissionService.withoutActions(permissions.visibleMenus(grantable, plans.subAppsOf(actor.tenantId())));
    }

    /** 替换租户的布局并审计，需要 {@link IamPermissions#MENU_MANAGE}。 */
    @Transactional
    public NavigationLayout replaceTenantLayout(TenantActor actor, NavigationLayout layout) {
        roles.requirePermission(actor.membershipId(), IamPermissions.MENU_MANAGE);
        validate(layout);
        writeTenant(actor, json.writeValueAsString(layout));
        audits.record(tenantAudit(actor, REPLACE));
        return layout;
    }

    /** 恢复租户的默认导航并审计，需要 {@link IamPermissions#MENU_MANAGE}。 */
    @Transactional
    public void resetTenantLayout(TenantActor actor) {
        roles.requirePermission(actor.membershipId(), IamPermissions.MENU_MANAGE);
        writeTenant(actor, null);
        audits.record(tenantAudit(actor, RESET));
    }

    /** 平台控制台的布局，供 {@code /platform/access} 下发给每个 Operator，不校验权限；没有布局时为 null。 */
    public NavigationLayout platformLayoutForAccess() {
        return read(layouts.findPlatformLayout());
    }

    /** 管理页读取平台的布局，需要 {@link PlatformPermissions#MENU_MANAGE}；没有布局时为 null。 */
    public NavigationLayout platformLayout(UUID operator) {
        platformRoles.requirePermission(operator, PlatformPermissions.MENU_MANAGE);
        return platformLayoutForAccess();
    }

    /** 替换平台的布局并审计，需要 {@link PlatformPermissions#MENU_MANAGE}。 */
    @Transactional
    public NavigationLayout replacePlatformLayout(UUID operator, NavigationLayout layout) {
        platformRoles.requirePermission(operator, PlatformPermissions.MENU_MANAGE);
        validate(layout);
        String content = json.writeValueAsString(layout);
        layouts.lockPlatform();
        if (layouts.updatePlatformLayout(content, operator) == 0) {
            layouts.insertPlatformLayout(UuidV7.generate(), content, operator);
        }
        audits.record(platformAudit(operator, REPLACE));
        return layout;
    }

    /** 恢复平台控制台的默认导航（逻辑删除布局）并审计，需要 {@link PlatformPermissions#MENU_MANAGE}。 */
    @Transactional
    public void resetPlatformLayout(UUID operator) {
        platformRoles.requirePermission(operator, PlatformPermissions.MENU_MANAGE);
        layouts.lockPlatform();
        layouts.deletePlatformLayout(operator);
        audits.record(platformAudit(operator, RESET));
    }

    private void writeTenant(TenantActor actor, String content) {
        if (!tenants.replaceNavigationLayout(actor.tenantId(), content, actor.identityId())) {
            // Token 中的租户在 iam 中必定已登记；未登记只可能是租户已被清理，与 Membership 失效同样按未认证处理。
            throw ForgeException.withErrorMessage(FrameworkErrorCode.AUTH_UNAUTHENTICATED, "Tenant unavailable");
        }
    }

    private NavigationLayout read(String content) {
        return content == null ? null : json.readValue(content, NavigationLayout.class);
    }

    private static AuditEvent tenantAudit(TenantActor actor, String action) {
        return AuditEvent.builder(AuditRealm.TENANT, AuditCategory.OPERATION, action)
                .tenant(actor.tenantId())
                .actor(actor.identityId())
                .resource("tenant", actor.tenantId())
                .build();
    }

    private static AuditEvent platformAudit(UUID operator, String action) {
        return AuditEvent.builder(AuditRealm.PLATFORM, AuditCategory.OPERATION, action)
                .actor(operator)
                .build();
    }

    // 只校验结构；引用是否存在、是否可见由前端合并时决定。
    private static void validate(NavigationLayout layout) {
        if (layout == null || layout.nodes() == null) throw invalid("nodes is required");
        validate(layout.nodes(), 1, new Counter(), new HashSet<>(), new HashSet<>(), new HashSet<>());
    }

    private static void validate(
            List<Node> nodes, int depth, Counter counter, Set<String> pages, Set<String> menus, Set<String> groups) {
        for (Node node : nodes) {
            if (node == null) throw invalid("null node");
            if (++counter.value > MAX_NODES) throw invalid("more than " + MAX_NODES + " nodes");
            if (depth > MAX_DEPTH) throw invalid("nested deeper than " + MAX_DEPTH + " levels");
            int references =
                    (node.page() == null ? 0 : 1) + (node.menu() == null ? 0 : 1) + (node.group() == null ? 0 : 1);
            if (references != 1) throw invalid("a node must have exactly one of page, menu and group");
            List<Node> children = node.children() == null ? List.of() : node.children();
            if (node.page() != null) {
                requireReference(node.page(), "page");
                if (!children.isEmpty()) throw invalid("page " + node.page() + " cannot have children");
                if (!pages.add(node.page())) throw invalid("duplicate page " + node.page());
            } else if (node.menu() != null) {
                requireReference(node.menu(), "menu");
                if (!menus.add(node.menu())) throw invalid("duplicate menu " + node.menu());
            } else {
                if (length(node.group()) < 1 || length(node.group()) > MAX_GROUP_ID) {
                    throw invalid("group id must be 1-" + MAX_GROUP_ID + " characters");
                }
                if (!groups.add(node.group())) throw invalid("duplicate group " + node.group());
                if (node.name() == null || node.name().isBlank() || length(node.name()) > MAX_GROUP_NAME) {
                    throw invalid("group name must be 1-" + MAX_GROUP_NAME + " characters");
                }
            }
            validate(children, depth + 1, counter, pages, menus, groups);
        }
    }

    private static void requireReference(String value, String kind) {
        if (value.isBlank()) throw invalid("blank " + kind);
    }

    // 按字符而不是 UTF-16 码元计数：表情等补充平面字符算一个。
    private static int length(String value) {
        return value.codePointCount(0, value.length());
    }

    private static ForgeException invalid(String reason) {
        return ForgeException.withErrorMessage(
                NavigationErrorCode.INVALID_LAYOUT, "Invalid navigation layout: " + reason);
    }

    private static final class Counter {
        int value;
    }
}
