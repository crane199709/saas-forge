package io.saasforge.gateway.envelope.filter;

import io.saasforge.gateway.envelope.service.ErrorEnvelopeService;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * 所有经过网关的响应都带 {@code X-Trace-Id}，与信封中的 {@code traceId} 相同，用户报障时可直接提供。
 *
 * <p>排在安全过滤链之前，401、403 与异常处理写出的响应同样带上；在提交响应前才写入，透传的下游响应也不例外。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class TraceIdResponseFilter implements WebFilter {

    static final String HEADER = "X-Trace-Id";

    private final ErrorEnvelopeService envelopes;

    TraceIdResponseFilter(ErrorEnvelopeService envelopes) {
        this.envelopes = envelopes;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        exchange.getResponse().beforeCommit(() -> {
            String traceId = envelopes.traceId(exchange);
            if (traceId != null) {
                exchange.getResponse().getHeaders().set(HEADER, traceId);
            }
            return Mono.empty();
        });
        return chain.filter(exchange);
    }
}
