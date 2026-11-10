package io.saasforge.gateway.logcontext.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.jwt.Jwt;

class RequestLogContextTest {
    @Test
    void nestedFailureLoggingRestoresTheOuterContext() {
        Map<String, String> original = MDC.getCopyOfContextMap();
        try {
            MDC.put("tenant_id", "outer-tenant");
            MDC.put("membership_id", "outer-member");
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/example"));
            var context = new RequestLogContext();
            context.capture(
                    exchange,
                    Jwt.withTokenValue("fixture")
                            .header("alg", "none")
                            .claim("tenant_id", "request-tenant")
                            .claim("membership_id", "request-member")
                            .build());
            try (RequestLogContext.Binding ignored = context.bind(exchange)) {
                assertThat(MDC.get("tenant_id")).isEqualTo("request-tenant");
                assertThat(MDC.get("membership_id")).isEqualTo("request-member");
            }
            assertThat(MDC.get("tenant_id")).isEqualTo("outer-tenant");
            assertThat(MDC.get("membership_id")).isEqualTo("outer-member");
        } finally {
            if (original == null) MDC.clear();
            else MDC.setContextMap(original);
        }
    }
}
