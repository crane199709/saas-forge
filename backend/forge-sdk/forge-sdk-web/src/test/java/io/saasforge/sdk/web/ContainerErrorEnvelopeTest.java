package io.saasforge.sdk.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

/** 过滤器层失败不经过 MVC 异常处理，由容器转发到 /error，同样输出信封。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class ContainerErrorEnvelopeTest {
    static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";

    @LocalServerPort
    int port;

    @Test
    void resourceFailureInFilterIs503Unavailable(CapturedOutput output) {
        ResponseEntity<String> response = get("/filter-failures/redis");

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody())
                .contains(
                        "\"code\":\"system.unavailable\"",
                        "\"httpStatus\":503",
                        "\"errorMessage\":\"Internal error\"",
                        "\"userTip\":\"服务暂时不可用，请稍后重试\"",
                        "\"traceId\":\"" + TRACE_ID + "\"")
                .doesNotContain("10.0.0.1");
        assertFailure(output, "/filter-failures/redis", 503, "system.unavailable", "WARN");
    }

    @Test
    void unknownFailureInFilterIs500WithoutDetails(CapturedOutput output) {
        ResponseEntity<String> response = get("/filter-failures/boom");

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody())
                .contains("\"code\":\"system.error\"", "\"userTip\":\"系统繁忙，请稍后重试\"")
                .doesNotContain("secret", "IllegalStateException");
        assertFailure(output, "/filter-failures/boom", 500, "system.error", "ERROR");
    }

    @Test
    void statusSentByFilterIsMappedToFrameworkCode(CapturedOutput output) {
        ResponseEntity<String> response = get("/filter-failures/rejected");

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody())
                .contains("\"code\":\"auth.forbidden\"", "\"httpStatus\":403", "\"userTip\":\"没有权限执行该操作\"");
        assertFailure(output, "/filter-failures/rejected", 403, "auth.forbidden", "INFO");
    }

    @ParameterizedTest
    @CsvSource({"500,system.error,ERROR", "503,system.unavailable,WARN"})
    void serverStatusWithoutExceptionAlsoHasTraceLog(int status, String code, String level, CapturedOutput output) {
        String path = "/filter-failures/status" + status;
        assertThat(get(path).getBody()).contains("\"traceId\":\"" + TRACE_ID + "\"");
        assertFailure(output, path, status, code, level);
    }

    @Test
    void directEnvelopeWriterLogsTheResponseTrace(CapturedOutput output) {
        assertThat(get("/filter-failures/unauthorized?password=query-secret")
                        .getStatusCode()
                        .value())
                .isEqualTo(401);
        assertFailure(output, "/filter-failures/unauthorized", 401, "auth.unauthenticated", "INFO");
        assertThat(output.getAll()).doesNotContain("query-secret", "credential-detail-not-for-logs");
    }

    private static void assertFailure(CapturedOutput output, String path, int status, String code, String level) {
        var failures = output.getAll()
                .lines()
                .filter(line -> line.contains("Request failed method="))
                .toList();
        assertThat(failures).hasSize(1);
        assertThat(failures.get(0))
                .contains(
                        level,
                        "method=GET",
                        "route=" + path,
                        "status=" + status,
                        "code=" + code,
                        "traceId=" + TRACE_ID);
    }

    private ResponseEntity<String> get(String path) {
        return RestClient.create("http://127.0.0.1:" + port)
                .get()
                .uri(path)
                .header("traceparent", "00-" + TRACE_ID + "-b7ad6b7169203331-01")
                .exchange((request, response) -> ResponseEntity.status(response.getStatusCode())
                        .body(new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)));
    }
}
