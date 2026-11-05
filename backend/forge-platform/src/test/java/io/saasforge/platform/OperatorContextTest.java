package io.saasforge.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;

/** 平台服务经 SDK 只接受 Platform Realm Token，并从中读取当前 Operator（ADR-0003）。 */
class OperatorContextTest extends PlatformIntegrationTest {
    @LocalServerPort
    int port;

    @Test
    void sdkVerifiesPlatformTokenAndExposesCurrentOperator() throws Exception {
        Map<?, ?> envelope = RestClient.create("http://localhost:" + port)
                .get()
                .uri("/operators/current")
                .header("Authorization", "Bearer " + PlatformTokens.operator())
                .retrieve()
                .body(Map.class);
        assertThat(envelope.get("code")).isEqualTo("ok");
        assertThat(((Map<?, ?>) envelope.get("data")).get("operatorId")).isEqualTo(PlatformTokens.OPERATOR_ID);
    }

    @Test
    void sdkRejectsTenantRealmAndIncompleteTokens() throws Exception {
        assertThat(status(null)).isEqualTo(401);
        // Tenant Realm 的 Token：issuer、audience 与 claim 都属于租户侧。
        assertThat(status(PlatformTokens.token(
                        "http://localhost:8080/iam", "tenant-app", "019a0110-0000-7000-8000-000000000001")))
                .isEqualTo(401);
        assertThat(status(PlatformTokens.token(PlatformTokens.PLATFORM_ISSUER, "tenant-app", null)))
                .isEqualTo(401);
        assertThat(status(PlatformTokens.token("http://other-realm.test", "platform-console", null)))
                .isEqualTo(401);
        // 没有版本的 Token 无法定位平台角色明细
        assertThat(status(PlatformTokens.token(
                        PlatformTokens.PLATFORM_ISSUER, "platform-console", null, PlatformTokens.OPERATOR_ID, null)))
                .isEqualTo(401);
    }

    private int status(String token) {
        var request = RestClient.create("http://localhost:" + port).get().uri("/operators/current");
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return request.exchange((req, response) -> response.getStatusCode().value());
    }
}
