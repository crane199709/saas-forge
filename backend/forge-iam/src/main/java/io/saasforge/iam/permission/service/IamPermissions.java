package io.saasforge.iam.permission.service;

import io.saasforge.sdk.core.ResourceManifest;
import io.saasforge.sdk.core.ResourceManifest.Permission;
import java.util.List;

/**
 * iam 自身的管理与成员消息读取 Permission Code。iam 不经 SDK 上报，启动时直接登记为服务 {@value #SERVICE} 的清单，
 * 与业务服务的权限码一样可授予自定义角色；Owner 与 Tenant Admin 拥有 Plan 范围内的有效权限。
 */
public final class IamPermissions {
    /** 管理角色、角色授权与成员的角色分配。 */
    public static final String ROLE_MANAGE = "iam:role:manage";

    /** 管理部门、岗位、扩展属性与成员的组织分配。 */
    public static final String ORG_MANAGE = "iam:org:manage";

    /** 邀请成员，停用、恢复与移除成员。 */
    public static final String MEMBER_MANAGE = "iam:member:manage";

    /** 查看本租户的审计记录。 */
    public static final String AUDIT_READ = "iam:audit:read";

    /** 管理 Extensible Dictionary 的字典项与可修改 Parameter 的覆盖值。 */
    public static final String REFERENCE_DATA_MANAGE = "iam:reference-data:manage";

    /** 只读查看字典变更历史。 */
    public static final String DICTIONARY_HISTORY_READ = "iam:dictionary-history:read";

    /** 管理租户的安全设置，如 MFA Requirement。 */
    public static final String SECURITY_MANAGE = "iam:security:manage";

    /** 调整本租户的 Navigation Layout。 */
    public static final String MENU_MANAGE = "iam:menu:manage";

    /** 查看当前成员待办。 */
    public static final String TODO_READ = "todo:read";

    /** 查看当前成员通知与维护已读状态。 */
    public static final String NOTIFICATION_READ = "notification:read";

    /** 查看当前租户可见公告与维护已读状态。 */
    public static final String ANNOUNCEMENT_READ = "announcement:read";

    /** 发布和撤回本租户公告。 */
    public static final String ANNOUNCEMENT_MANAGE = "announcement:manage";

    /** 查看数据分级、脱敏规则与敏感字段登记。 */
    public static final String DATA_SECURITY_READ = "iam:data-security:read";

    /** 管理数据安全配置。 */
    public static final String DATA_SECURITY_MANAGE = "iam:data-security:manage";

    /** iam 登记清单使用的服务名。这些管理权限码不受套餐限制：Tenant Admin 始终能管理成员与角色。 */
    public static final String SERVICE = "forge-iam";
    // 同一版本的清单内容不可变：改动权限码、名称或分组时必须升级版本，否则启动时登记失败。
    private static final String VERSION = "10";
    private static final String GROUP = "租户管理";

    private IamPermissions() {}

    /** IAM 的管理权限不受套餐限制，成员消息读取权限仍受 Feature Pack 裁剪。 */
    public static boolean planScoped(String code) {
        return List.of(TODO_READ, NOTIFICATION_READ, ANNOUNCEMENT_READ).contains(code);
    }

    static ResourceManifest manifest() {
        return new ResourceManifest(
                SERVICE,
                VERSION,
                List.of(
                        new Permission(MEMBER_MANAGE, "管理成员", GROUP),
                        new Permission(ORG_MANAGE, "管理组织", GROUP),
                        new Permission(ROLE_MANAGE, "管理角色", GROUP),
                        new Permission(AUDIT_READ, "查看审计", GROUP),
                        new Permission(REFERENCE_DATA_MANAGE, "管理字典与参数", GROUP),
                        new Permission(DICTIONARY_HISTORY_READ, "查看字典变更历史", GROUP),
                        new Permission(SECURITY_MANAGE, "管理安全设置", GROUP),
                        new Permission(TODO_READ, "查看待办", "消息读取"),
                        new Permission(NOTIFICATION_READ, "查看通知", "消息读取"),
                        new Permission(ANNOUNCEMENT_READ, "查看公告", "消息读取"),
                        new Permission(ANNOUNCEMENT_MANAGE, "管理公告", GROUP),
                        new Permission(DATA_SECURITY_READ, "查看数据安全", GROUP),
                        new Permission(DATA_SECURITY_MANAGE, "管理数据安全", GROUP),
                        new Permission(MENU_MANAGE, "管理菜单", GROUP)),
                List.of());
    }
}
