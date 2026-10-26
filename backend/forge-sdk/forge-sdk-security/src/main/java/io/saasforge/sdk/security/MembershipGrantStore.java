package io.saasforge.sdk.security;

import io.saasforge.sdk.core.MembershipGrants;
import io.saasforge.sdk.core.MembershipStateKeys;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * 按 {@code membership_id + version} 读取 iam 发布的角色与权限明细，并在本地缓存（ADR-0005）。
 *
 * <p>每个 Membership 只缓存一个版本：Token 的 {@code ver} 变化即重新读取，不需要失效通知。
 * 是否允许该版本访问由网关负责，本类只按 Token 声明的版本取明细。
 */
public final class MembershipGrantStore {
    private static final int MAXIMUM_SIZE = 10_000;

    private final StringRedisTemplate redis;
    private final JsonMapper json = JsonMapper.builder().build();
    // 访问顺序的 LinkedHashMap 实现 LRU，避免长期运行时按 Membership 数量无限增长。
    private final Map<UUID, Cached> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, Cached> eldest) {
            return size() > MAXIMUM_SIZE;
        }
    };

    /**
     * 创建读取器。
     *
     * @param redis 与 iam 共用的 Redis；明细键是全局键，不使用租户前缀模板
     */
    public MembershipGrantStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 当前请求 Membership 的明细。
     *
     * @return 当前 Token 版本下的角色与权限
     * @throws IllegalStateException 不在已认证请求中，或 iam 未发布该版本的明细
     */
    public MembershipGrants current() {
        return grants(TenantContext.current());
    }

    /**
     * 指定上下文的明细；版本与缓存不同时从 Redis 重新读取。
     *
     * @param context 已验签 Token 的上下文
     * @return 该版本下的角色与权限
     * @throws IllegalStateException iam 未发布该版本的明细
     */
    public MembershipGrants grants(TenantContext context) {
        synchronized (cache) {
            Cached cached = cache.get(context.membershipId());
            if (cached != null && cached.version() == context.version()) return cached.grants();
        }
        String value = redis.opsForValue()
                .get(MembershipStateKeys.grants(context.tenantId(), context.membershipId(), context.version()));
        // 缺失时不能当作“没有任何权限”缓存下来：那会把发布延迟变成该版本整个生命周期内的拒绝。
        if (value == null) throw new IllegalStateException("Membership grants are not published");
        MembershipGrants grants = json.readValue(value, MembershipGrants.class);
        synchronized (cache) {
            cache.put(context.membershipId(), new Cached(context.version(), grants));
        }
        return grants;
    }

    private record Cached(long version, MembershipGrants grants) {}
}
