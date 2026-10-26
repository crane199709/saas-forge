package io.saasforge.iam.membership.service;

import io.saasforge.iam.membership.mapper.MembershipMapper;
import io.saasforge.iam.membership.model.MembershipChange;
import io.saasforge.sdk.core.MembershipGrants;
import io.saasforge.sdk.core.MembershipStateKeys;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * 维护 Membership 在 Redis 中的 version 与角色权限明细（ADR-0005）。
 *
 * <p>变更先提交数据库再写 Redis。Redis 写入失败时调用方收到异常，刷新已按数据库状态拒绝，
 * 但已签发的 Access Token 在过期前仍会通过网关，需重试变更以重新发布。
 */
@Service
public class MembershipStateService {
    // 明细只需覆盖按该版本签发的 Access Token 的有效期（15 分钟），每次签发都会续期。
    private static final Duration GRANTS_TTL = Duration.ofHours(1);
    // 只允许版本前进：签发时读到的旧版本不能覆盖并发变更已发布的新版本，否则旧 Token 会重新生效。
    private static final DefaultRedisScript<Long> ADVANCE_VERSION = new DefaultRedisScript<>(
            "local current = redis.call('get', KEYS[1]) "
                    + "if current and tonumber(current) >= tonumber(ARGV[1]) then return 0 end "
                    + "redis.call('set', KEYS[1], ARGV[1]) return 1",
            Long.class);

    private final MembershipMapper memberships;
    private final StringRedisTemplate redis;
    private final JsonMapper json;

    MembershipStateService(MembershipMapper memberships, StringRedisTemplate redis, JsonMapper json) {
        this.memberships = memberships;
        this.redis = redis;
        this.json = json;
    }

    /** 签发 Token 时发布数据库中的当前状态，使被淘汰或丢失的 Redis 状态随刷新恢复。 */
    public void publish(UUID tenantId, UUID membershipId, long version) {
        // TODO(#16): 角色管理接入后发布该版本实际的角色与权限明细。
        var grants = new MembershipGrants(Set.of(), Set.of());
        redis.opsForValue()
                .set(
                        MembershipStateKeys.grants(tenantId, membershipId, version),
                        json.writeValueAsString(grants),
                        GRANTS_TTL);
        redis.execute(
                ADVANCE_VERSION, List.of(MembershipStateKeys.version(tenantId, membershipId)), String.valueOf(version));
    }

    /** 停用后该 Membership 的 Token 立即失效，且不能再刷新或被选择进入。 */
    public void deactivate(UUID membershipId) {
        publishChange(membershipId, memberships.deactivate(membershipId));
    }

    /** 角色或权限变更后调用：旧版本 Token 立即失效，刷新后按新版本签发。 */
    public void incrementVersion(UUID membershipId) {
        publishChange(membershipId, memberships.incrementVersion(membershipId));
    }

    private void publishChange(UUID membershipId, MembershipChange change) {
        if (change == null) throw new MembershipNotFoundException();
        publish(change.tenantId(), membershipId, change.version());
    }
}
