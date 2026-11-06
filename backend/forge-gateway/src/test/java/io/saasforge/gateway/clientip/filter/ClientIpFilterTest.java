package io.saasforge.gateway.clientip.filter;

import static org.assertj.core.api.Assertions.assertThat;

import io.saasforge.sdk.core.ClientIpHeader;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** 网关确定客户端 IP 并转发给下游：客户端自报的值一律不采信，只有可信代理追加的 X-Forwarded-For 才用于还原真实地址。 */
class ClientIpFilterTest {
    private static final String LOCAL_PROXY = "127\\.0\\.0\\.1|10\\.0\\.0\\.\\d+";

    @Test
    void aDirectClientIsIdentifiedByItsConnectionWhateverItClaims() {
        var request = from("203.0.113.7")
                .header(ClientIpHeader.NAME, "198.51.100.1")
                .header("X-Forwarded-For", "198.51.100.2");
        assertThat(forwarded(LOCAL_PROXY, request)).isEqualTo("203.0.113.7");
    }

    @Test
    void behindTrustedProxiesTheRightmostUntrustedAddressIsTheClient() {
        // 最左一项是客户端自带的伪造值，nginx（10.0.0.2）追加真实地址，内层代理（10.0.0.3）再追加 nginx。
        var request = from("127.0.0.1").header("X-Forwarded-For", "198.51.100.2, 203.0.113.7, 10.0.0.2", "10.0.0.3");
        assertThat(forwarded(LOCAL_PROXY, request)).isEqualTo("203.0.113.7");
    }

    @Test
    void aTrustedPeerWithoutForwardedAddressesIsItselfTheClient() {
        assertThat(forwarded(LOCAL_PROXY, from("127.0.0.1"))).isEqualTo("127.0.0.1");
    }

    @Test
    void withoutTrustedProxiesForwardedAddressesAreIgnored() {
        var request = from("127.0.0.1").header("X-Forwarded-For", "198.51.100.2");
        assertThat(forwarded(null, request)).isEqualTo("127.0.0.1");
    }

    private static MockServerHttpRequest.BaseBuilder<?> from(String peer) {
        return MockServerHttpRequest.get("/iam/auth/login").remoteAddress(new InetSocketAddress(peer, 40000));
    }

    private static String forwarded(String trustedProxies, MockServerHttpRequest.BaseBuilder<?> request) {
        var passed = new AtomicReference<ServerWebExchange>();
        new ClientIpFilter(trustedProxies)
                .filter(MockServerWebExchange.from(request), exchange -> {
                    passed.set(exchange);
                    return Mono.empty();
                })
                .block();
        return passed.get().getRequest().getHeaders().getFirst(ClientIpHeader.NAME);
    }
}
