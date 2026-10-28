package io.saasforge.iam.permission.service;

import io.saasforge.sdk.core.ResourceManifest;
import io.saasforge.sdk.core.ResourceManifest.Permission;
import java.util.List;

/**
 * iam 自身的管理类 Permission Code。iam 不经 SDK 上报，启动时直接登记为服务 {@value #SERVICE} 的清单，
 * 与业务服务的权限码一样可授予自定义角色；Owner 与 Tenant Admin 随之拥有。
 */
public final class IamPermissions {
    /** 管理角色、角色授权与成员的角色分配。 */
    public static final String ROLE_MANAGE = "iam:role:manage";

    /** 管理部门、岗位、扩展属性与成员的组织分配。 */
    public static final String ORG_MANAGE = "iam:org:manage";

    static final String SERVICE = "forge-iam";
    // 同一版本的清单内容不可变：改动权限码、名称或分组时必须升级版本，否则启动时登记失败。
    private static final String VERSION = "1";
    private static final String GROUP = "租户管理";

    private IamPermissions() {}

    static ResourceManifest manifest() {
        return new ResourceManifest(
                SERVICE,
                VERSION,
                List.of(new Permission(ORG_MANAGE, "管理组织", GROUP), new Permission(ROLE_MANAGE, "管理角色", GROUP)),
                List.of());
    }
}
