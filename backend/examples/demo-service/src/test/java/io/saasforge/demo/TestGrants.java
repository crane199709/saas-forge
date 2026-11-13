package io.saasforge.demo;

import io.saasforge.sdk.abac.DataScope;
import io.saasforge.sdk.core.MembershipGrants;
import io.saasforge.sdk.core.MembershipStateKeys;
import io.saasforge.sdk.core.SubjectAttributes;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * 模拟 iam 按 Token 版本发布的 Membership 权限明细（ADR-0005），TestTokens 签发的 Token 版本默认为 1。
 *
 * <p>不指定数据范围时按 Tenant Admin 发布，订单为「全部」：数据权限默认拒绝，不关心数据权限的测试不能因此看不到数据。
 */
final class TestGrants {
    static final List<String> ALL_ORDER_PERMISSIONS = List.of(
            "order:read",
            "order:create",
            "order:update",
            "order:delete",
            "order:import",
            "order:importRollback",
            "order:export");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private TestGrants() {}

    static void publish(StringRedisTemplate redis, UUID tenantId, UUID membershipId, List<String> permissions) {
        publish(redis, tenantId, membershipId, 1, permissions, SubjectAttributes.NONE, List.of(DataScope.ALL));
    }

    /** 以给定版本发布明细；每个数据范围相当于一个持有该订单 Data Scope 的 Role，为空表示没有任何策略。 */
    static void publish(
            StringRedisTemplate redis,
            UUID tenantId,
            UUID membershipId,
            long version,
            List<String> permissions,
            SubjectAttributes subject,
            List<DataScope> orderScopes) {
        publishPolicies(
                redis,
                tenantId,
                membershipId,
                version,
                permissions,
                subject,
                orderScopes.stream().map(DataScope::template).toList());
    }

    /** 以给定版本发布明细；每条订单策略（JSON 表达式树）相当于一个持有该策略的 Role，如编辑器保存的自定义条件。 */
    static void publishPolicies(
            StringRedisTemplate redis,
            UUID tenantId,
            UUID membershipId,
            long version,
            List<String> permissions,
            SubjectAttributes subject,
            List<String> orderPolicies) {
        Map<String, List<String>> policies = orderPolicies.isEmpty() ? Map.of() : Map.of("order", orderPolicies);
        redis.opsForValue()
                .set(
                        MembershipStateKeys.grants(tenantId, membershipId, version),
                        JSON.writeValueAsString(
                                new MembershipGrants(Set.of(), Set.copyOf(permissions), subject, policies)));
    }

    static void publishAll(StringRedisTemplate redis, UUID tenantId, UUID membershipId) {
        publish(redis, tenantId, membershipId, ALL_ORDER_PERMISSIONS);
    }
}
