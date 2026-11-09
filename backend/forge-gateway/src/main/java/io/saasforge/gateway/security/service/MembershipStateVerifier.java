package io.saasforge.gateway.security.service;

import io.saasforge.sdk.core.MembershipStateKeys;
import io.saasforge.sdk.core.TenantIpAllowlist;
import io.saasforge.sdk.core.api.ForgeException;
import io.saasforge.sdk.core.api.FrameworkErrorCode;
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
 * <p>只有试用与正常的租户可访问，停用、过期等状态即时生效。版本或租户状态缺失返回 401，iam 刷新时补齐；IP 规则缺失或不匹配返回 403，避免触发刷新循环。
 */
@Service
public class MembershipStateVerifier {
    private final ReactiveStringRedisTemplate redis;

    MembershipStateVerifier(ReactiveStringRedisTemplate redis) {
        this.redis = redis;
    }

    public Mono<Jwt> verify(Jwt jwt, String clientIp) {
        // TenantJwtValidator 已保证这些 claim 存在且格式正确。
        UUID tenantId = UUID.fromString(jwt.getClaimAsString("tenant_id"));
        UUID membershipId = UUID.fromString(jwt.getClaimAsString("membership_id"));
        long version = ((Number) jwt.getClaim("ver")).longValue();
        // 一次 MGET 同时读取版本、租户状态与 IP 规则，每个请求只多一次 Redis 往返。
        return redis.opsForValue()
                .multiGet(List.of(
                        MembershipStateKeys.version(tenantId, membershipId),
                        MembershipStateKeys.tenantStatus(tenantId),
                        MembershipStateKeys.tenantIpAllowlist(tenantId)))
                // Redis 故障不能表现为 401：那会触发刷新，而刷新同样依赖 Redis，最终把用户登出。
                .onErrorMap(error -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, null, error))
                .flatMap(state -> {
                    if (!String.valueOf(version).equals(state.get(0))
                            || !MembershipStateKeys.accessible(state.get(1))) {
                        return Mono.error(new BadJwtException("Membership version or tenant status changed"));
                    }
                    try {
                        if (TenantIpAllowlist.decode(state.get(2)).allows(clientIp)) return Mono.just(jwt);
                    } catch (IllegalArgumentException ignored) {
                        // 缺失或非法发布值与限制不匹配一样拒绝，绝不能退回无限制。
                    }
                    return Mono.error(new ForgeException(FrameworkErrorCode.TENANT_IP_NOT_ALLOWED));
                });
    }
}
