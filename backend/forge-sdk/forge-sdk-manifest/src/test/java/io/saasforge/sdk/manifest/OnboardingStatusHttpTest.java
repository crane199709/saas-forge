package io.saasforge.sdk.manifest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;

@SpringBootTest(
        classes = OnboardingStatusHttpTest.Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.application.name=demo-service",
            "forge.manifest.version=1",
            "forge.manifest.iam-uri=http://127.0.0.1:1",
            "forge.manifest.token=manifest-secret",
            "forge.ops.token=ops-secret",
            "forge.security.issuer=http://tenant.test",
            "forge.security.jwk-set-uri=http://localhost/jwks"
        })
class OnboardingStatusHttpTest {
    @LocalServerPort
    int port;

    @Test
    void iamFailureDoesNotPreventStartupAndStatusUsesIndependentOpsCredential() {
        var client = RestClient.create("http://localhost:" + port);
        for (String token : new String[] {"", "manifest-secret", "ordinary-user-jwt"}) {
            int status = client.get()
                    .uri("/internal/onboarding-status")
                    .header("Authorization", "Bearer " + token)
                    .exchange((q, r) -> r.getStatusCode().value());
            assertThat(status).isEqualTo(401);
        }
        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            var body = client.get()
                    .uri("/internal/onboarding-status")
                    .header("Authorization", "Bearer ops-secret")
                    .retrieve()
                    .body(String.class);
            assertThat(body)
                    .contains("NETWORK_UNREACHABLE", "REPORTING")
                    .doesNotContain("manifest-secret", "ops-secret", "127.0.0.1", "iamUri");
        });
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class Application {}
}
