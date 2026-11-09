package io.saasforge.sdk.core;

import java.util.UUID;

/**
 * iam 在 Redis 发布的即时失效状态的键约定（ADR-0005），iam 写入，网关与业务服务只读。
 *
 * <p>键按 {@code iam:{tenant_id}:} 归属租户；读取方从已验签 Token 取得租户与 Membership，不接受客户端传入。
 */
public final class MembershipStateKeys {
    /** 租户正常状态的值。 */
    public static final String ACTIVE = "active";

    /** 租户试用状态的值，与正常状态一样可被访问。 */
    public static final String TRIAL = "trial";

    private MembershipStateKeys() {}

    /**
     * 租户状态是否允许成员访问：只有试用与正常可以，其他任何值或键缺失（{@code null}）都视为不可访问。
     *
     * @param status {@link #tenantStatus(UUID)} 的值
     * @return 可访问时为 true
     */
    public static boolean accessible(String status) {
        return ACTIVE.equals(status) || TRIAL.equals(status);
    }

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
     * 租户当前状态，取值同 Tenant Lifecycle 状态（{@code pending}、{@code trial}、{@code active}、{@code suspended}、
     * {@code expired}、{@code archived}、{@code deleted}），由 iam 按 platform 广播的状态变更写入。
     *
     * @param tenantId 租户标识
     * @return Redis 键
     */
    public static String tenantStatus(UUID tenantId) {
        return "iam:" + tenantId + ":status";
    }

    /**
     * 租户 IP 允许列表，值由 {@link TenantIpAllowlist#encode()} 定义；缺失时读取方必须拒绝访问。
     *
     * @param tenantId 租户标识
     * @return Redis 键
     */
    public static String tenantIpAllowlist(UUID tenantId) {
        return "iam:" + tenantId + ":ip-allowlist";
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
