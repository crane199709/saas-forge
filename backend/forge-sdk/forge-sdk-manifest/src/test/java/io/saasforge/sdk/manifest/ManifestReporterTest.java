package io.saasforge.sdk.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.saasforge.sdk.core.ResourceManifest;
import io.saasforge.sdk.core.ResourceManifest.Permission;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ManifestReporterTest {
    static final ResourceManifest MANIFEST = new ResourceManifest(
            "demo-service", "1.0.0", List.of(new Permission("order:read", "查看订单", "订单")), List.of());

    HttpServer iam;
    final AtomicReference<String> authorization = new AtomicReference<>();
    final AtomicReference<String> body = new AtomicReference<>();

    @AfterEach
    void stop() {
        iam.stop(0);
    }

    @Test
    void postsTheManifestToIamWithTheServiceToken() throws Exception {
        String base = start(201, "{\"code\":\"ok\",\"httpStatus\":201}");

        new ManifestReporter(base, "demo-token").report(MANIFEST);

        assertThat(authorization.get()).isEqualTo("Bearer demo-token");
        JsonNode json = JsonMapper.builder().build().readTree(body.get());
        assertThat(json.get("service").asString()).isEqualTo("demo-service");
        assertThat(json.get("version").asString()).isEqualTo("1.0.0");
        assertThat(json.get("permissions").get(0).get("name").asString()).isEqualTo("查看订单");
    }

    @Test
    void rejectedReportFailsWithTheErrorCode() throws Exception {
        String base = start(409, "{\"code\":\"manifest.versionConflict\",\"httpStatus\":409}");

        assertThatThrownBy(() -> new ManifestReporter(base, "demo-token").report(MANIFEST))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("409")
                .hasMessageContaining("manifest.versionConflict");
    }

    private String start(int status, String response) throws Exception {
        iam = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        iam.createContext("/internal/manifests", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var stream = exchange.getResponseBody()) {
                stream.write(bytes);
            }
        });
        iam.start();
        return "http://127.0.0.1:" + iam.getAddress().getPort();
    }
}
