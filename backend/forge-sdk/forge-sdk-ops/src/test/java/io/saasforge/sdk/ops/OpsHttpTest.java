package io.saasforge.sdk.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.saasforge.sdk.core.api.ApiResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

@SpringBootTest(
        classes = OpsHttpTest.Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "forge.ops.token=service-ops-token",
            "forge.security.issuer=http://tenant.test",
            "forge.security.jwk-set-uri=http://localhost/jwks"
        })
class OpsHttpTest {
    @LocalServerPort
    int port;

    @Test
    void serviceTokenReachesOpsRouteWhileBusinessRoutesStillRequireJwt() {
        var client = RestClient.create("http://localhost:" + port);
        var response = client.get()
                .uri("/internal/ops")
                .header("Authorization", "Bearer service-ops-token")
                .exchange((request, result) ->
                        Map.of("status", result.getStatusCode().value(), "body", result.bodyTo(String.class)));
        assertThat(response.get("status")).isEqualTo(200);
        assertThat(response.get("body").toString()).contains("\"data\":true");
        int status = client.get()
                .uri("/business")
                .exchange((request, result) -> result.getStatusCode().value());
        assertThat(status).isEqualTo(401);
    }

    @Test
    void wrongOrMissingTokenIsUnauthorizedForEveryOpsRoute() {
        var client = RestClient.create("http://localhost:" + port);
        for (String path : List.of("/internal/ops", "/internal/ops/future-resource", "/internal/onboarding-status")) {
            for (String token : List.of("wrong-token", "")) {
                var response = client.get()
                        .uri(path)
                        .header("Authorization", "Bearer " + token)
                        .exchange((request, result) ->
                                Map.of("status", result.getStatusCode().value(), "body", result.bodyTo(String.class)));
                assertThat(response.get("status")).isEqualTo(401);
                assertThat(response.get("body").toString()).contains("auth.unauthenticated");
            }
        }
    }

    @Test
    void unresolvedEnvironmentTokenPreventsStartup() {
        assertThatThrownBy(() -> {
                    try (var ignored = new SpringApplicationBuilder(Application.class)
                            .run(
                                    "--forge.ops.token=${FORGE_MQ01_TEST_MISSING_OPS_TOKEN}",
                                    "--forge.security.issuer=http://tenant.test",
                                    "--forge.security.jwk-set-uri=http://localhost/jwks",
                                    "--server.port=0")) {}
                })
                .hasStackTraceContaining("forge.ops.token must be set");
    }

    @Test
    void missingOrBlankOpsTokenPreventsStartup() {
        assertThatThrownBy(() -> {
                    try (var ignored = new SpringApplicationBuilder(Application.class)
                            .run(
                                    "--forge.ops.token=",
                                    "--forge.security.issuer=http://tenant.test",
                                    "--forge.security.jwk-set-uri=http://localhost/jwks",
                                    "--server.port=0")) {}
                })
                .hasStackTraceContaining("forge.ops.token must be set");
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(BusinessController.class)
    static class Application {}

    @RestController
    static class BusinessController {
        @GetMapping("/business")
        ApiResponse<Boolean> business() {
            return ApiResponse.ok(true);
        }
    }
}
