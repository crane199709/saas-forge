package io.saasforge.sdk.core;

import java.util.UUID;

/**
 * iam 在 Redis 发布的 Operator 即时失效状态的键约定，与 {@link MembershipStateKeys} 对应（ADR-0005），iam 写入，网关与
 * Platform Realm 服务只读。
 *
 * <p>Operator 不属于任何租户，键以 {@code iam:platform:} 开头，与租户键（{@code iam:<tenant_id>:}）不会重叠。
 */
public final class OperatorStateKeys {
    private OperatorStateKeys() {}

    /**
     * Operator 当前 {@code version}，十进制字符串；与 Platform Realm Token 的 {@code ver} 不一致即失效。
     * 停用、角色、授权、Data Policy 或负责区域变更时递增。
     *
     * @param operatorId Operator 标识
     * @return Redis 键
     */
    public static String version(UUID operatorId) {
        return "iam:platform:operator:" + operatorId + ":version";
    }

    /**
     * 某一 {@code version} 下 Operator 的平台角色、权限、Data Policy 与主体属性明细（{@link MembershipGrants} 的 JSON）。
     *
     * @param operatorId Operator 标识
     * @param version Token 中的 {@code ver}
     * @return Redis 键
     */
    public static String grants(UUID operatorId, long version) {
        return "iam:platform:operator:" + operatorId + ":grants:" + version;
    }
}
