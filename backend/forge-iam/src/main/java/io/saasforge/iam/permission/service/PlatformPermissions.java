package io.saasforge.iam.permission.service;

import io.saasforge.sdk.core.ResourceManifest;
import io.saasforge.sdk.core.ResourceManifest.Permission;
import java.util.List;

/**
 * iam 自身供 Operator 使用的管理类 Permission Code，属于 Platform Realm。与 {@link IamPermissions} 一样在启动时直接登记，
 * 服务名单独为 {@value #SERVICE}：每个服务只有一个当前清单，而一份清单只属于一个 Realm。
 */
public final class PlatformPermissions {
    /** 创建、编辑、停用与恢复 Operator，重置密码，分配平台角色。 */
    public static final String OPERATOR_MANAGE = "platform:operator:manage";

    /** 管理平台角色及其授权与 Data Policy。 */
    public static final String ROLE_MANAGE = "platform:role:manage";

    /** 按登录标识查找 Identity 并重置其 MFA。重置 Operator 的 MFA 只有超级管理员可以做，不另设权限码。 */
    public static final String IDENTITY_MFA_RESET = "platform:identity-mfa:reset";

    /** 查看 Platform Realm 的审计记录。 */
    public static final String AUDIT_READ = "platform:audit:read";

    /** 登记清单使用的服务名。 */
    public static final String SERVICE = "forge-iam-platform";
    // 同 IamPermissions：改动权限码、名称或分组时必须升级版本。
    private static final String VERSION = "3";
    private static final String GROUP = "平台管理";

    private PlatformPermissions() {}

    static ResourceManifest manifest() {
        return new ResourceManifest(
                SERVICE,
                VERSION,
                List.of(
                        new Permission(OPERATOR_MANAGE, "管理运营账号", GROUP),
                        new Permission(ROLE_MANAGE, "管理平台角色", GROUP),
                        new Permission(AUDIT_READ, "查看平台审计", GROUP),
                        new Permission(IDENTITY_MFA_RESET, "重置身份 MFA", GROUP)),
                List.of(),
                List.of(),
                List.of(),
                ResourceManifest.PLATFORM_REALM);
    }
}
