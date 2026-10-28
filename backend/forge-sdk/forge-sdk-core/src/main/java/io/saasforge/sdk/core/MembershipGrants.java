package io.saasforge.sdk.core;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 某一 {@code version} 下 Membership 的角色、权限与主体属性明细，经 Redis 在 iam 与业务服务之间传递，不进入 JWT（ADR-0005）。
 *
 * <p>主体属性与权限随同一版本发布：部门、岗位或扩展属性分配变化时 iam 递增版本，业务服务随即读到新的主体属性。
 *
 * @param roles 角色编码
 * @param permissions Permission Code
 * @param subject 主体属性；缺失时为 {@link SubjectAttributes#NONE}
 * @param dataPolicies 按 Data Resource 编码分组的 Data Policy（JSON 表达式树），每个持有策略的 Role 一条，多条取并集；
 *     没有某个资源的条目即没有任何策略，对该资源的数据一律不可见（默认拒绝）
 */
public record MembershipGrants(
        Set<String> roles,
        Set<String> permissions,
        SubjectAttributes subject,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, List<String>> dataPolicies) {
    /** 复制为不可变集合，缓存中的明细不能被调用方修改；缺失的策略视为空，兼容不带数据策略的旧明细。 */
    public MembershipGrants {
        roles = Set.copyOf(roles);
        permissions = Set.copyOf(permissions);
        subject = subject == null ? SubjectAttributes.NONE : subject;
        dataPolicies = dataPolicies == null ? Map.of() : copy(dataPolicies);
    }

    private static Map<String, List<String>> copy(Map<String, List<String>> policies) {
        var copied = new HashMap<String, List<String>>();
        policies.forEach((resource, list) -> copied.put(resource, List.copyOf(list)));
        return Map.copyOf(copied);
    }
}
