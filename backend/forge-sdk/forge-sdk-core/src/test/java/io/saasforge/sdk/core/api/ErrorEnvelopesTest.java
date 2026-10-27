package io.saasforge.sdk.core.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.tracing.Tracer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class ErrorEnvelopesTest {

    @Test
    void missingOrUnparsableAcceptLanguageUsesZhCn() {
        assertThat(ErrorEnvelopes.localeOf(null)).isEqualTo(Locale.SIMPLIFIED_CHINESE);
        assertThat(ErrorEnvelopes.localeOf(" ")).isEqualTo(Locale.SIMPLIFIED_CHINESE);
        assertThat(ErrorEnvelopes.localeOf("*")).isEqualTo(Locale.SIMPLIFIED_CHINESE);
        assertThat(ErrorEnvelopes.localeOf("en;q=abc")).isEqualTo(Locale.SIMPLIFIED_CHINESE);
    }

    @Test
    void preferredAcceptLanguageWins() {
        assertThat(ErrorEnvelopes.localeOf("en-US;q=0.8, fr;q=0.9")).isEqualTo(Locale.FRENCH);
    }

    @Test
    void frameworkEnvelopeUsesSdkTipAndOmitsNullFields() {
        ApiResponse<Void> body = ErrorEnvelopes.framework(
                        FrameworkErrorCode.SYSTEM_UNAVAILABLE, ErrorEnvelopes.INTERNAL_ERROR, Locale.SIMPLIFIED_CHINESE)
                .withTraceId("trace-1");

        assertThat(new String(ErrorEnvelopes.toJson(body), StandardCharsets.UTF_8))
                .isEqualTo("{\"code\":\"system.unavailable\",\"httpStatus\":503,\"errorMessage\":\"Internal error\","
                        + "\"userTip\":\"服务暂时不可用，请稍后重试\",\"traceId\":\"trace-1\"}");
    }

    @Test
    void frameworkEnvelopeCanKeepTheActualClientErrorStatus() {
        ApiResponse<Void> body = ErrorEnvelopes.framework(
                FrameworkErrorCode.REQUEST_MALFORMED, 415, "UnsupportedMediaType", Locale.SIMPLIFIED_CHINESE);

        assertThat(body.httpStatus()).isEqualTo(415);
        assertThat(body.code()).isEqualTo("request.malformed");
        assertThat(body.userTip()).isEqualTo("请求格式不正确");
    }

    @Test
    void traceIdIsAbsentWithoutTracer() {
        assertThat(ErrorEnvelopes.traceId((Tracer) null)).isNull();
    }
}
