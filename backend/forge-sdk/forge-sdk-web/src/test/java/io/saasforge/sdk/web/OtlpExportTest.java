package io.saasforge.sdk.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

/** 配置 OTLP 地址后，trace 与 Micrometer 指标推送到采集端；本期没有采集后端，用本地 HTTP 端点代替。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OtlpExportTest {
    static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";
    static final List<Received> RECEIVED = new CopyOnWriteArrayList<>();
    static final HttpServer COLLECTOR;

    static {
        try {
            COLLECTOR = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            COLLECTOR.createContext("/", exchange -> {
                RECEIVED.add(new Received(
                        exchange.getRequestURI().getPath(),
                        exchange.getRequestBody().readAllBytes()));
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            });
            COLLECTOR.start();
        } catch (Exception error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    @DynamicPropertySource
    static void otlp(DynamicPropertyRegistry registry) {
        String collector = "http://127.0.0.1:" + COLLECTOR.getAddress().getPort();
        registry.add("management.opentelemetry.tracing.export.otlp.endpoint", () -> collector + "/v1/traces");
        registry.add("management.opentelemetry.tracing.export.schedule-delay", () -> "100ms");
        registry.add("management.tracing.sampling.probability", () -> "1.0");
        registry.add("management.otlp.metrics.export.enabled", () -> "true");
        registry.add("management.otlp.metrics.export.url", () -> collector + "/v1/metrics");
        registry.add("management.otlp.metrics.export.step", () -> "1s");
    }

    @AfterAll
    static void stop() {
        COLLECTOR.stop(0);
    }

    @LocalServerPort
    int port;

    @Test
    void requestTraceAndHttpMetricsAreExported() {
        RestClient.create("http://localhost:" + port)
                .get()
                .uri("/orders/o-1")
                .header("traceparent", "00-" + TRACE_ID + "-b7ad6b7169203331-01")
                .retrieve()
                .toBodilessEntity();

        // OTLP 以 protobuf 编码，trace id 是 16 字节原值，指标名是 UTF-8 字符串
        byte[] traceId = HexFormat.of().parseHex(TRACE_ID);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(RECEIVED).anyMatch(r -> r.path().equals("/v1/traces") && contains(r.body(), traceId));
            assertThat(RECEIVED)
                    .anyMatch(r -> r.path().equals("/v1/metrics")
                            && contains(r.body(), "http.server.requests".getBytes(StandardCharsets.UTF_8)));
        });
    }

    private static boolean contains(byte[] body, byte[] part) {
        outer:
        for (int i = 0; i <= body.length - part.length; i++) {
            for (int j = 0; j < part.length; j++) {
                if (body[i + j] != part[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    record Received(String path, byte[] body) {}
}
