package io.saasforge.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestClient;

class ServiceCredentialOpsTest extends PlatformIntegrationTest {
    @LocalServerPort
    int port;

    @Test
    void allCredentialOperationsRequireManagePermission() throws Exception {
        String token = PlatformTokens.operator(UUID.randomUUID(), List.of("platform:ops:manage"), null, List.of());
        for (String path : List.of(
                "", "/019a0110-0000-7000-8000-000000000001/disable", "/019a0110-0000-7000-8000-000000000001/rotate")) {
            call(token, "POST", path, Map.of("service", "new-service"), 403);
        }
        call(token, "GET", "", null, 403);
    }

    @Test
    void verifiedOperatorReplacesAnyBrowserSuppliedActorAndNewServiceNeedsNoManifest() throws Exception {
        var response = call(
                PlatformTokens.operator(),
                "POST",
                "",
                Map.of("service", "new-service", "actor", UUID.randomUUID()),
                201);
        assertThat(((Map<?, ?>) response.get("data")).get("token")).isEqualTo("test-one-time-secret");
        call(PlatformTokens.operator(), "POST", "/019a0110-0000-7000-8000-000000000001/disable", null, 200);
        call(PlatformTokens.operator(), "POST", "/019a0110-0000-7000-8000-000000000001/rotate", null, 201);
    }

    @Test
    void invalidServicesReturnFieldValidationErrorsBeforeCallingIam() throws Exception {
        for (String[] invalid : new String[][] {
            {null, "validation.notBlank"},
            {"", "validation.notBlank"},
            {"  ", "validation.notBlank"},
            {"Invalid_Name", "validation.pattern"},
            {"a".repeat(101), "validation.size"}
        }) {
            var request = invalid[0] == null ? Map.of() : Map.of("service", invalid[0]);
            var response = call(PlatformTokens.operator(), "POST", "", request, 400);
            assertThat(response).containsEntry("code", "request.invalid").containsEntry("httpStatus", 400);
            assertThat((List<Map<String, Object>>) response.get("errors"))
                    .anySatisfy(error ->
                            assertThat(error).containsEntry("field", "service").containsEntry("code", invalid[1]));
        }
    }

    @Test
    void iamUnavailabilityIsReportedAsServiceState() throws Exception {
        try {
            ServiceCredentialOpsFixture.available = false;
            var response = call(PlatformTokens.operator(), "GET", "", null, 200);
            assertThat(((Map<?, ?>) response.get("data")).get("available")).isEqualTo(false);
            assertThat(((Map<?, ?>) response.get("data")).get("service")).isEqualTo("forge-iam");
            call(PlatformTokens.operator(), "POST", "", Map.of("service", "new-service"), 503);
        } finally {
            ServiceCredentialOpsFixture.available = true;
        }
    }

    Map call(String token, String method, String path, Object body, int status) {
        var request = RestClient.create("http://127.0.0.1:" + port)
                .method(HttpMethod.valueOf(method))
                .uri("/service-credentials" + path)
                .headers(h -> h.setBearerAuth(token));
        if (body != null) {
            request.body(body);
        }
        return request.exchange((req, response) -> {
            assertThat(response.getStatusCode().value()).isEqualTo(status);
            return response.bodyTo(Map.class);
        });
    }
}
