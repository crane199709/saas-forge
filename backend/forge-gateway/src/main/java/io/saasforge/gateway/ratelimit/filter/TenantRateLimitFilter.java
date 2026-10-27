package io.saasforge.gateway.ratelimit.filter;

import io.saasforge.gateway.envelope.service.ErrorEnvelopeService;
import io.saasforge.gateway.ratelimit.service.TenantRateLimiter;
import io.saasforge.sdk.core.api.FrameworkErrorCode;
import java.util.UUID;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * 按 Tenant Realm Token 中的 {@code tenant_id} 限流，超限返回 429 {@code request.rateLimited} 信封。
 *
 * <p>排在安全过滤链（order -100）之后：租户只取自验签通过的 Token。匿名请求与 Platform Realm Token 没有租户，不在这里限流。
 */
@Component
@Order(0)
class TenantRateLimitFilter implements WebFilter {

    private final TenantRateLimiter limiter;
    private final ErrorEnvelopeService envelopes;

    TenantRateLimitFilter(TenantRateLimiter limiter, ErrorEnvelopeService envelopes) {
        this.limiter = limiter;
        this.envelopes = envelopes;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return exchange.getPrincipal()
                .filter(JwtAuthenticationToken.class::isInstance)
                // Platform Realm Token 同样是 JwtAuthenticationToken，但没有 tenant_id
                .mapNotNull(principal ->
                        ((JwtAuthenticationToken) principal).getToken().getClaimAsString("tenant_id"))
                .map(tenantId -> limiter.tryAcquire(UUID.fromString(tenantId)))
                .defaultIfEmpty(true)
                .flatMap(allowed -> allowed ? chain.filter(exchange) : reject(exchange));
    }

    private Mono<Void> reject(ServerWebExchange exchange) {
        exchange.getResponse()
                .getHeaders()
                .set(HttpHeaders.RETRY_AFTER, String.valueOf(TenantRateLimiter.WINDOW_SECONDS));
        return envelopes.write(exchange, FrameworkErrorCode.REQUEST_RATE_LIMITED, "Tenant rate limit exceeded");
    }
}
