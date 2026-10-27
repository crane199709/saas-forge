package io.saasforge.sdk.core;

import java.util.Set;

/**
 * 某一 {@code version} 下 Membership 的角色、权限与主体属性明细，经 Redis 在 iam 与业务服务之间传递，不进入 JWT（ADR-0005）。
 *
 * <p>主体属性与权限随同一版本发布：部门、岗位或扩展属性分配变化时 iam 递增版本，业务服务随即读到新的主体属性。
 *
 * @param roles 角色编码
 * @param permissions Permission Code
 * @param subject 主体属性；缺失时为 {@link SubjectAttributes#NONE}
 */
public record MembershipGrants(Set<String> roles, Set<String> permissions, SubjectAttributes subject) {
    /** 复制为不可变集合，缓存中的明细不能被调用方修改。 */
    public MembershipGrants {
        roles = Set.copyOf(roles);
        permissions = Set.copyOf(permissions);
        subject = subject == null ? SubjectAttributes.NONE : subject;
    }
}
