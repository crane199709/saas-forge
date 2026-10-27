package io.saasforge.sdk.core.api;

import io.micrometer.observation.Observation;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.handler.TracingObservationHandler;
import java.util.List;
import java.util.Locale;
import tools.jackson.databind.json.JsonMapper;

/**
 * 在 Spring MVC 之外写出失败信封时共用的部分：安全过滤链、Servlet 错误写出器与 WebFlux 网关。只放与 Web 栈无关的逻辑，
 * 各栈自行负责设置状态码与写出字节。
 */
public final class ErrorEnvelopes {

    /** 默认语言：请求未带 {@code Accept-Language} 时使用，不随部署机器的 locale 变化。 */
    public static final Locale DEFAULT_LOCALE = Locale.SIMPLIFIED_CHINESE;

    /** 5xx 与未知异常固定使用的 {@code errorMessage}，不输出异常信息。 */
    public static final String INTERNAL_ERROR = "Internal error";

    // 信封字段固定且不受服务的 Jackson 定制影响，独立实例即可，避免依赖服务是否声明 JsonMapper Bean
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ErrorEnvelopes() {}

    /**
     * 按 {@code Accept-Language} 取请求语言。
     *
     * @param acceptLanguage 请求头原值，可为 null
     * @return 权重最高的语言；未提供、为 {@code *} 或无法解析时为 {@link #DEFAULT_LOCALE}
     */
    public static Locale localeOf(String acceptLanguage) {
        if (acceptLanguage == null || acceptLanguage.isBlank()) {
            return DEFAULT_LOCALE;
        }
        try {
            List<Locale.LanguageRange> ranges = Locale.LanguageRange.parse(acceptLanguage);
            String range = ranges.isEmpty() ? "*" : ranges.get(0).getRange();
            return "*".equals(range) ? DEFAULT_LOCALE : Locale.forLanguageTag(range);
        } catch (IllegalArgumentException e) {
            return DEFAULT_LOCALE;
        }
    }

    /**
     * 框架级错误码的失败信封，{@code userTip} 取 SDK 自带文案，状态码取错误码固定的状态码。
     *
     * @param code 错误码
     * @param errorMessage 面向开发者的说明
     * @param locale 请求语言
     * @return 尚未填入 traceId 的失败信封
     */
    public static ApiResponse<Void> framework(ErrorCode code, String errorMessage, Locale locale) {
        return framework(code, code.httpStatus(), errorMessage, locale);
    }

    /**
     * 同 {@link #framework(ErrorCode, String, Locale)}，但使用指定状态码：未单独定义 code 的框架 4xx 保留实际状态码。
     *
     * @param code 错误码
     * @param httpStatus 实际状态码
     * @param errorMessage 面向开发者的说明
     * @param locale 请求语言
     * @return 尚未填入 traceId 的失败信封
     */
    public static ApiResponse<Void> framework(ErrorCode code, int httpStatus, String errorMessage, Locale locale) {
        String userTip =
                FrameworkMessages.find(code.code(), locale).orElseGet(() -> FrameworkMessages.fallback(locale));
        return ApiResponse.error(httpStatus, code.code(), errorMessage, userTip, null);
    }

    /**
     * 序列化信封，null 字段不输出。
     *
     * @param body 信封
     * @return UTF-8 JSON
     */
    public static byte[] toJson(ApiResponse<?> body) {
        return JSON.writeValueAsBytes(body);
    }

    /**
     * 线程上当前 span 的 trace id，供 Servlet 栈使用；响应式链路跨线程执行，不能用本方法。
     *
     * @param tracer Micrometer Tracer，未启用 tracing 时为 null
     * @return trace id，没有 tracer 或当前 span 时为 null
     */
    public static String traceId(Tracer tracer) {
        if (tracer == null) {
            return null;
        }
        TraceContext context = tracer.currentTraceContext().context();
        return context == null ? null : context.traceId();
    }

    /**
     * 本次请求的观测上下文中记录的 trace id。用于线程上已没有当前 span 的场景：响应式链路跨线程执行，Servlet 容器转发到
     * {@code /error} 时请求的观测 scope 也已关闭。
     *
     * @param context 框架为本次请求创建的观测上下文
     * @return trace id，未启用 tracing 时为 null
     */
    public static String traceId(Observation.Context context) {
        TracingObservationHandler.TracingContext tracing = context.get(TracingObservationHandler.TracingContext.class);
        Span span = tracing == null ? null : tracing.getSpan();
        return span == null ? null : span.context().traceId();
    }
}
