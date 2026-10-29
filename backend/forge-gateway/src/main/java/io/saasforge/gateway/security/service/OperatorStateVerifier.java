package io.saasforge.gateway.security.service;

import io.saasforge.sdk.core.OperatorStateKeys;
import java.util.UUID;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * 比对已验签 Platform Realm Token 的 {@code ver} 与 iam 在 Redis 发布的 Operator 版本（ADR-0005）：停用、角色或授权变更后
 * 旧 Token 立即失效。版本缺失按失效处理，前端收到 401 后刷新，iam 签发时补齐。
 */
@Service
public class OperatorStateVerifier {
    private final ReactiveStringRedisTemplate redis;

    OperatorStateVerifier(ReactiveStringRedisTemplate redis) {
        this.redis = redis;
    }

    public Mono<Jwt> verify(Jwt jwt) {
        // PlatformJwtValidator 已保证 sub 为 UUID、ver 为数字。
        UUID operatorId = UUID.fromString(jwt.getSubject());
        long version = ((Number) jwt.getClaim("ver")).longValue();
        return redis.opsForValue()
                .get(OperatorStateKeys.version(operatorId))
                .defaultIfEmpty("")
                // 理由同 MembershipStateVerifier：Redis 故障不能表现为 401。
                .onErrorMap(error -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, null, error))
                .flatMap(current -> String.valueOf(version).equals(current)
                        ? Mono.just(jwt)
                        : Mono.error(new BadJwtException("Operator version changed")));
    }
}
