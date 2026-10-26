package io.saasforge.gateway.security.service;

import io.saasforge.sdk.core.MembershipStateKeys;
import java.util.List;
import java.util.UUID;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * 比对已验签 Token 的 {@code ver} 与 iam 在 Redis 发布的 Membership 版本和租户状态（ADR-0005）。
 *
 * <p>状态缺失按失效处理：前端收到 401 后刷新，iam 签发时会补齐状态。
 */
@Service
public class MembershipStateVerifier {
    private final ReactiveStringRedisTemplate redis;

    MembershipStateVerifier(ReactiveStringRedisTemplate redis) {
        this.redis = redis;
    }

    public Mono<Jwt> verify(Jwt jwt) {
        // TenantJwtValidator 已保证这些 claim 存在且格式正确。
        UUID tenantId = UUID.fromString(jwt.getClaimAsString("tenant_id"));
        UUID membershipId = UUID.fromString(jwt.getClaimAsString("membership_id"));
        long version = ((Number) jwt.getClaim("ver")).longValue();
        // 一次 MGET 同时读取两项，每个请求只多一次 Redis 往返。
        return redis.opsForValue()
                .multiGet(List.of(
                        MembershipStateKeys.version(tenantId, membershipId),
                        MembershipStateKeys.tenantStatus(tenantId)))
                // Redis 故障不能表现为 401：那会触发刷新，而刷新同样依赖 Redis，最终把用户登出。
                .onErrorMap(error -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, null, error))
                .flatMap(state ->
                        String.valueOf(version).equals(state.get(0)) && MembershipStateKeys.ACTIVE.equals(state.get(1))
                                ? Mono.just(jwt)
                                : Mono.error(new BadJwtException("Membership version or tenant status changed")));
    }
}
