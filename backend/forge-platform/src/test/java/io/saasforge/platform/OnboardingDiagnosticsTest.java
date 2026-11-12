package io.saasforge.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.alibaba.nacos.api.config.ConfigService;
import io.saasforge.platform.onboarding.service.GatewayDeclarationSource;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestClient;

@Import(OnboardingDiagnosticsTest.Config.class)
class OnboardingDiagnosticsTest extends PlatformIntegrationTest {
    @LocalServerPort
    int port;

    static volatile String serverStatus = "UP";
    static volatile boolean disconnectDuringRead;
    static final String YAML = """
 spring:
   cloud:
     gateway:
       server:
         webflux:
           routes:
             - uri: lb://demo-service
               predicates: [Path=/demo/**]
               filters: [StripPrefix=1]
             - uri: lb://unsupported-service
               predicates: [Path=/html/**]
             - uri: lb://wrong-service
               predicates: [Path=/wrong/**]
 forge:
   gateway:
     services:
       - {prefix: /demo, realm: tenant}
       - {prefix: /html, realm: tenant}
       - {prefix: /wrong, realm: tenant}
 """;

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean
        @Primary
        GatewayDeclarationSource testSource() {
            ConfigService nacos = (ConfigService) Proxy.newProxyInstance(
                    ConfigService.class.getClassLoader(),
                    new Class[] {ConfigService.class},
                    (p, m, a) -> switch (m.getName()) {
                        case "getServerStatus" -> serverStatus;
                        case "getConfig" -> {
                            if (disconnectDuringRead) serverStatus = "DOWN";
                            yield YAML;
                        }
                        default -> null;
                    });
            return new GatewayDeclarationSource(nacos, "DEFAULT_GROUP");
        }
    }

    @Test
    void permissionIsIndependentFromOpsAndTenantRealmCannotRead() throws Exception {
        String ops = PlatformTokens.operator(UUID.randomUUID(), List.of("platform:ops:read"), null, List.of());
        assertThat(status(ops)).isEqualTo(403);
        assertThat(status(PlatformTokens.token(
                        "http://tenant-realm.test",
                        "tenant-app",
                        UUID.randomUUID().toString())))
                .isEqualTo(401);
    }

    @Test
    void realProbesKeepThreeLevelsIndependentAndIsolateOfflineServices() throws Exception {
        var body = tools.jackson.databind.json.JsonMapper.builder().build().readTree(read());
        var services = body.path("data").path("services");
        var demo = find(services, "demo-service");
        assertThat(demo.path("configuration").path("status").asString()).isEqualTo("CONFIGURED");
        assertThat(demo.path("runtime").path("reporting").path("phase").asString())
                .isEqualTo("REPORTED");
        assertThat(demo.path("request").path("status").asString()).isEqualTo("VERIFIED");
        assertThat(find(services, "offline-service")
                        .path("runtime")
                        .path("status")
                        .asString())
                .isEqualTo("UNAVAILABLE");
        assertThat(find(services, "unsupported-service")
                        .path("request")
                        .path("detail")
                        .asString())
                .isEqualTo("HTML_OR_INVALID_OPENAPI");
        assertThat(find(services, "wrong-service")
                        .path("request")
                        .path("detail")
                        .asString())
                .isEqualTo("SERVICE_IDENTITY_MISMATCH");
        assertThat(body.toString()).doesNotContain("demo-ops-token", "test-platform-token", "127.0.0.1");
    }

    private static tools.jackson.databind.JsonNode find(tools.jackson.databind.JsonNode services, String service) {
        for (var item : services) if (item.path("service").asString().equals(service)) return item;
        throw new AssertionError("Missing service " + service);
    }

    @Test
    void nacosConnectionLostDuringReadCannotCertifySnapshot() throws Exception {
        disconnectDuringRead = true;
        try {
            assertThat(read()).contains("NACOS_UNAVAILABLE").doesNotContain("CONFIGURED");
        } finally {
            disconnectDuringRead = false;
            serverStatus = "UP";
        }
    }

    @Test
    void directoryFailureKeepsCandidatesAndSuccessfulIndependentProbes() throws Exception {
        PlatformTokens.servicesAvailable = false;
        try {
            assertThat(read()).contains("\"directoryAvailable\":false", "demo-service", "VERIFIED");
        } finally {
            PlatformTokens.servicesAvailable = true;
        }
    }

    @Test
    void nacosDownNeverTurnsClientSnapshotIntoConfiguredEvidence() throws Exception {
        serverStatus = "DOWN";
        try {
            assertThat(read()).contains("UNKNOWN", "NACOS_UNAVAILABLE").doesNotContain("CONFIGURED");
        } finally {
            serverStatus = "UP";
        }
    }

    private int status(String token) {
        return RestClient.create("http://localhost:" + port)
                .get()
                .uri("/onboarding")
                .header("Authorization", "Bearer " + token)
                .exchange((q, r) -> r.getStatusCode().value());
    }

    private String read() throws Exception {
        return RestClient.create("http://localhost:" + port)
                .get()
                .uri("/onboarding")
                .header("Authorization", "Bearer " + PlatformTokens.operator())
                .retrieve()
                .body(String.class);
    }
}
