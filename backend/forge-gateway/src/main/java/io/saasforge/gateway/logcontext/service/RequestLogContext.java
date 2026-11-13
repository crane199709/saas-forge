package io.saasforge.gateway.logcontext.service;

import io.micrometer.context.ContextRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.MDC;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ServerWebExchange;
import reactor.util.context.Context;

/**
 * 网关日志的租户上下文：与 Servlet 服务的 SDK 一致，日志 MDC 带上 Token 中的 {@code tenant_id} 与 {@code membership_id}。
 *
 * <p>响应式链路会换线程，MDC 不能直接写：值放进 Reactor context，由 context-propagation 在每次切换线程时恢复到 MDC
 * （需 {@code spring.reactor.context-propagation=auto}）。异常处理在过滤链之外执行，读不到链内写入的 context，因此同时记在请求属性上，
 * 由 {@link #bind(ServerWebExchange)} 在记录日志时绑定。
 */
@Service
public class RequestLogContext {
    /** 与 forge-sdk-core 的 CurrentTenant、forge-sdk-security 的 CurrentTenantFilter 使用相同的 MDC 键。 */
    static final List<String> CLAIMS = List.of("tenant_id", "membership_id");

    private static final String ATTRIBUTE = RequestLogContext.class.getName();

    static {
        for (String key : CLAIMS) {
            ContextRegistry.getInstance()
                    .registerThreadLocalAccessor(
                            key, () -> MDC.get(key), value -> MDC.put(key, value), () -> MDC.remove(key));
        }
    }

    /**
     * 记下已验签 Token 中的租户与 Membership，并返回需写入 Reactor context 的值。
     *
     * @param exchange 当前请求
     * @param jwt 已验签的 Token
     * @return 写入后的 context 变换；Platform Realm Token 没有这些 claim，不写入任何值
     */
    public Function<Context, Context> capture(ServerWebExchange exchange, Jwt jwt) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String key : CLAIMS) {
            String value = jwt.getClaimAsString(key);
            if (value != null) {
                values.put(key, value);
            }
        }
        exchange.getAttributes().put(ATTRIBUTE, values);
        return context -> context.putAllMap(values);
    }

    /**
     * 在当前线程绑定请求的日志上下文，供过滤链之外（异常处理）记录日志时使用。
     *
     * @param exchange 当前请求
     * @return 关闭时恢复原值，避免在过滤链内记录失败摘要时破坏外层上下文
     */
    public Binding bind(ServerWebExchange exchange) {
        Map<String, String> values = exchange.getAttributeOrDefault(ATTRIBUTE, Map.of());
        Map<String, String> previous = new LinkedHashMap<>();
        values.keySet().forEach(key -> previous.put(key, MDC.get(key)));
        values.forEach(MDC::put);
        return () -> previous.forEach((key, value) -> {
            if (value == null) {
                MDC.remove(key);
            } else {
                MDC.put(key, value);
            }
        });
    }

    /** {@link #bind(ServerWebExchange)} 的句柄。 */
    @FunctionalInterface
    public interface Binding extends AutoCloseable {
        @Override
        void close();
    }
}
