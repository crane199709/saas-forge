package io.saasforge.gateway.clientip.filter;

import io.saasforge.sdk.core.ClientIpHeader;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.filter.headers.TrustedProxies;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * 确定客户端 IP，以 {@link ClientIpHeader#NAME} 转发给下游（iam 的 Login Lockout 据此按 IP 限速），并覆盖客户端自带的值。
 *
 * <p>连接来自可信代理（{@code trusted-proxies}，与网关处理 X-Forwarded-* 的配置相同）时，取 {@code X-Forwarded-For} 中从右往左第一个不可信的地址，
 * 更靠左的项可由客户端伪造；否则连接地址就是客户端。
 */
@Component
@Order(-200)
class ClientIpFilter implements WebFilter {
    private final TrustedProxies trustedProxies;

    @Autowired
    ClientIpFilter(GatewayProperties properties) {
        this(properties.getTrustedProxies());
    }

    ClientIpFilter(String trustedProxies) {
        this.trustedProxies = trustedProxies == null || trustedProxies.isBlank()
                ? address -> false
                : TrustedProxies.from(trustedProxies);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String client = clientOf(exchange.getRequest());
        ServerHttpRequest request = exchange.getRequest()
                .mutate()
                .headers(headers -> {
                    headers.remove(ClientIpHeader.NAME);
                    if (client != null) headers.set(ClientIpHeader.NAME, client);
                })
                .build();
        return chain.filter(exchange.mutate().request(request).build());
    }

    private String clientOf(ServerHttpRequest request) {
        InetSocketAddress remote = request.getRemoteAddress();
        if (remote == null) return null;
        String client = remote.getHostString();
        if (!trustedProxies.isTrusted(client)) return client;
        List<String> forwarded = forwardedAddresses(request);
        for (int i = forwarded.size() - 1; i >= 0; i--) {
            client = forwarded.get(i);
            if (!trustedProxies.isTrusted(client)) return client;
        }
        return client;
    }

    // 同名头可能有多个，每个又可能以逗号分隔多个地址。
    private static List<String> forwardedAddresses(ServerHttpRequest request) {
        List<String> addresses = new ArrayList<>();
        for (String value : request.getHeaders().getOrEmpty("X-Forwarded-For")) {
            for (String address : value.split(",")) {
                if (!address.isBlank()) addresses.add(address.strip());
            }
        }
        return addresses;
    }
}
