package io.saasforge.gateway.logcontext.filter;

import io.saasforge.gateway.logcontext.service.RequestLogContext;
import java.util.function.Function;
import org.springframework.core.annotation.Order;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * 已认证请求的后续处理（限流、路由、转发）日志带上 Token 中的租户与 Membership。
 *
 * <p>排在安全过滤链（order -100）之后、限流（order 0）之前：值只取自验签通过的 Token。
 */
@Component
@Order(-50)
class RequestLogContextFilter implements WebFilter {

    private final RequestLogContext logContext;

    RequestLogContextFilter(RequestLogContext logContext) {
        this.logContext = logContext;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return exchange.getPrincipal()
                .filter(JwtAuthenticationToken.class::isInstance)
                .map(principal -> logContext.capture(exchange, ((JwtAuthenticationToken) principal).getToken()))
                .defaultIfEmpty(Function.identity())
                .flatMap(context -> chain.filter(exchange).contextWrite(context));
    }
}
