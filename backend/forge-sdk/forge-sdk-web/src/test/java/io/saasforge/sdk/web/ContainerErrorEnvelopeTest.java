package io.saasforge.sdk.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

/** 过滤器层失败不经过 MVC 异常处理，由容器转发到 /error，同样输出信封。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ContainerErrorEnvelopeTest {
    static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";

    @LocalServerPort
    int port;

    @Test
    void resourceFailureInFilterIs503Unavailable() {
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
    }

    @Test
    void unknownFailureInFilterIs500WithoutDetails() {
        ResponseEntity<String> response = get("/filter-failures/boom");

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody())
                .contains("\"code\":\"system.error\"", "\"userTip\":\"系统繁忙，请稍后重试\"")
                .doesNotContain("secret", "IllegalStateException");
    }

    @Test
    void statusSentByFilterIsMappedToFrameworkCode() {
        ResponseEntity<String> response = get("/filter-failures/rejected");

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody())
                .contains("\"code\":\"auth.forbidden\"", "\"httpStatus\":403", "\"userTip\":\"没有权限执行该操作\"");
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
