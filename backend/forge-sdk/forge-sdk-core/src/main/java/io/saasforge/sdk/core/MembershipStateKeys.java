package io.saasforge.sdk.core;

import java.util.UUID;

/**
 * iam 在 Redis 发布的即时失效状态的键约定（ADR-0005），iam 写入，网关与业务服务只读。
 *
 * <p>键按 {@code iam:{tenant_id}:} 归属租户；读取方从已验签 Token 取得租户与 Membership，不接受客户端传入。
 */
public final class MembershipStateKeys {
    /** 租户可被访问时的状态值；其他任何值或键缺失都视为不可访问。 */
    public static final String ACTIVE = "active";

    private MembershipStateKeys() {}

    /**
     * Membership 当前 {@code version}，十进制字符串；与 Token 的 {@code ver} 不一致即失效。
     *
     * @param tenantId Membership 所在租户
     * @param membershipId Membership 标识
     * @return Redis 键
     */
    public static String version(UUID tenantId, UUID membershipId) {
        return "iam:" + tenantId + ":membership:" + membershipId + ":version";
    }

    /**
     * 租户当前状态，取值同 iam {@code tenant.status}。
     *
     * @param tenantId 租户标识
     * @return Redis 键
     */
    public static String tenantStatus(UUID tenantId) {
        return "iam:" + tenantId + ":status";
    }

    /**
     * 某一 {@code version} 下 Membership 的角色与权限明细（JSON），版本号进入键名，变更后旧明细自然不再被读取。
     *
     * @param tenantId Membership 所在租户
     * @param membershipId Membership 标识
     * @param version Token 中的 {@code ver}
     * @return Redis 键
     */
    public static String grants(UUID tenantId, UUID membershipId, long version) {
        return "iam:" + tenantId + ":membership:" + membershipId + ":grants:" + version;
    }
}
