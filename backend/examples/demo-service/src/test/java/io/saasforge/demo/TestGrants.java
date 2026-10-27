package io.saasforge.demo;

import io.saasforge.sdk.core.MembershipGrants;
import io.saasforge.sdk.core.MembershipStateKeys;
import io.saasforge.sdk.core.SubjectAttributes;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

/** 模拟 iam 按 Token 版本发布的 Membership 权限明细（ADR-0005），TestTokens 签发的 Token 版本固定为 1。 */
final class TestGrants {
    static final List<String> ALL_ORDER_PERMISSIONS =
            List.of("order:read", "order:create", "order:update", "order:delete");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private TestGrants() {}

    static void publish(StringRedisTemplate redis, UUID tenantId, UUID membershipId, List<String> permissions) {
        redis.opsForValue()
                .set(
                        MembershipStateKeys.grants(tenantId, membershipId, 1),
                        JSON.writeValueAsString(
                                new MembershipGrants(Set.of(), Set.copyOf(permissions), SubjectAttributes.NONE)));
    }

    static void publishAll(StringRedisTemplate redis, UUID tenantId, UUID membershipId) {
        publish(redis, tenantId, membershipId, ALL_ORDER_PERMISSIONS);
    }
}
