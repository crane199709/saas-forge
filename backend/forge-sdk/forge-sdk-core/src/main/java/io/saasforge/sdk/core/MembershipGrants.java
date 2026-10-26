package io.saasforge.sdk.core;

import java.util.Set;

/**
 * 某一 {@code version} 下 Membership 的角色与权限明细，经 Redis 在 iam 与业务服务之间传递，不进入 JWT（ADR-0005）。
 *
 * @param roles 角色编码
 * @param permissions Permission Code
 */
public record MembershipGrants(Set<String> roles, Set<String> permissions) {
    /** 复制为不可变集合，缓存中的明细不能被调用方修改。 */
    public MembershipGrants {
        roles = Set.copyOf(roles);
        permissions = Set.copyOf(permissions);
    }
}
