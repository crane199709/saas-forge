package io.saasforge.sdk.security;

import io.saasforge.sdk.core.MembershipGrants;
import io.saasforge.sdk.core.MembershipStateKeys;
import io.saasforge.sdk.core.SubjectAttributes;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
 *
 * <p>同一版本的明细也会变：业务服务新上报或下线 Permission Code 后，iam 在下一次签发 Token 时以相同版本覆盖明细，
 * 不递增版本。因此本地缓存最多保留 {@link #REFRESH_AFTER}，之后重新读取。
 */
public final class MembershipGrantStore {
    /** 本地缓存的明细超过该时长后重新从 Redis 读取。 */
    public static final Duration REFRESH_AFTER = Duration.ofMinutes(1);

    private static final int MAXIMUM_SIZE = 10_000;

    private final StringRedisTemplate redis;
    private final Clock clock;
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
        this(redis, Clock.systemUTC());
    }

    MembershipGrantStore(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
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
     * 当前请求 Membership 的主体属性，键约定见 {@link SubjectAttributes#toContext(UUID)}，
     * 供数据权限求值与 SQL 编译使用。
     *
     * @return 可变的主体上下文
     * @throws IllegalStateException 不在已认证请求中，或 iam 未发布该版本的明细
     */
    public Map<String, Object> currentSubject() {
        TenantContext context = TenantContext.current();
        return grants(context).subject().toContext(context.membershipId());
    }

    /**
     * 指定上下文的明细；版本与缓存不同或缓存已超过 {@link #REFRESH_AFTER} 时从 Redis 重新读取。
     *
     * @param context 已验签 Token 的上下文
     * @return 该版本下的角色与权限
     * @throws IllegalStateException iam 未发布该版本的明细
     */
    public MembershipGrants grants(TenantContext context) {
        Instant now = clock.instant();
        synchronized (cache) {
            Cached cached = cache.get(context.membershipId());
            if (cached != null
                    && cached.version() == context.version()
                    && now.isBefore(cached.loadedAt().plus(REFRESH_AFTER))) {
                return cached.grants();
            }
        }
        String value = redis.opsForValue()
                .get(MembershipStateKeys.grants(context.tenantId(), context.membershipId(), context.version()));
        // 缺失时不能当作“没有任何权限”缓存下来：那会把发布延迟变成该版本整个生命周期内的拒绝。
        if (value == null) throw new IllegalStateException("Membership grants are not published");
        MembershipGrants grants = json.readValue(value, MembershipGrants.class);
        synchronized (cache) {
            cache.put(context.membershipId(), new Cached(context.version(), grants, now));
        }
        return grants;
    }

    private record Cached(long version, MembershipGrants grants, Instant loadedAt) {}
}
