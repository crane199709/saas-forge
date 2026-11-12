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
        if (iam != null) iam.stop(0);
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
        // 没有角色模板时不输出该字段，与加入角色模板前上报并存储的清单内容相同。
        assertThat(json.has("roles")).isFalse();
    }

    @Test
    void rejectionClassifiesOnlyRecognizedStatusAndBusinessCodePairs() throws Exception {
        String base = start(409, "{\"code\":\"manifest.versionConflict\",\"httpStatus\":409}");
        assertThatThrownBy(() -> new ManifestReporter(base, "demo-token").report(MANIFEST))
                .isInstanceOf(ManifestReportException.class)
                .satisfies(error -> assertThat(((ManifestReportException) error).reason())
                        .isEqualTo(ManifestFailure.VERSION_CONFLICT));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "401,auth.unauthenticated,CREDENTIAL_REJECTED",
        "403,manifest.serviceMismatch,SERVICE_MISMATCH",
        "403,auth.forbidden,HTTP_REJECTED",
        "409,other.conflict,HTTP_REJECTED",
        "400,request.invalid,INVALID_MANIFEST",
        "503,system.unavailable,HTTP_REJECTED"
    })
    void classifiesHttpFailures(int status, String code, ManifestFailure expected) throws Exception {
        String base = start(status, "{\"code\":\"" + code + "\"}");
        assertThatThrownBy(() -> new ManifestReporter(base, "secret-token").report(MANIFEST))
                .isInstanceOf(ManifestReportException.class)
                .satisfies(error ->
                        assertThat(((ManifestReportException) error).reason()).isEqualTo(expected))
                .hasMessageNotContaining("secret-token")
                .hasMessageNotContaining(base);
    }

    @Test
    void unreachableIamIsClassified() throws Exception {
        String base = start(200, "{}");
        iam.stop(0);
        assertThatThrownBy(() -> new ManifestReporter(base, "secret-token").report(MANIFEST))
                .isInstanceOf(ManifestReportException.class)
                .satisfies(error -> assertThat(((ManifestReportException) error).reason())
                        .isEqualTo(ManifestFailure.NETWORK_UNREACHABLE));
    }

    @Test
    void htmlSuccessIsNotAnAcceptedManifestReport() throws Exception {
        String base = start(200, "<html>Vite</html>");
        assertThatThrownBy(() -> new ManifestReporter(base, "demo-token").report(MANIFEST))
                .isInstanceOf(ManifestReportException.class)
                .satisfies(error -> assertThat(((ManifestReportException) error).reason())
                        .isEqualTo(ManifestFailure.UNEXPECTED_RESPONSE));
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
