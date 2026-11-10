package io.saasforge.sdk.core.api;

import org.slf4j.Logger;
import org.slf4j.event.Level;

/** Servlet 服务与 WebFlux 网关共用的失败摘要；只接收响应元数据，不接收请求体、凭证或错误文案。 */
public final class FailureLogs {
    private FailureLogs() {}

    /**
     * 记录一条失败日志。4xx 固定为 INFO 且不附带堆栈；5xx 使用调用方原有的级别与异常。
     *
     * @param logger 错误产生位置的日志器
     * @param level 5xx 的日志级别
     * @param method 请求方法
     * @param route 路由模板，未匹配路由时使用请求路径，不含查询参数
     * @param body 最终失败信封，traceId 必须与响应一致
     * @param failure 5xx 原始异常，没有异常时为 null
     */
    public static void write(
            Logger logger, Level level, String method, String route, ApiResponse<?> body, Throwable failure) {
        if (body.httpStatus() < 400) return;
        boolean serverError = body.httpStatus() >= 500;
        // 路径可能来自未匹配的请求，不能让查询值或控制字符进入单行摘要。
        String path = route == null ? null : route.split("[?#]", 2)[0];
        logger.atLevel(serverError ? level : Level.INFO)
                .setCause(serverError ? failure : null)
                .log(
                        "Request failed method={} route={} status={} code={} traceId={}",
                        clean(method),
                        clean(path),
                        body.httpStatus(),
                        clean(body.code()),
                        clean(body.traceId()));
    }

    private static String clean(String value) {
        if (value == null) return "-";
        return value.substring(0, Math.min(value.length(), 512)).replaceAll("[\\p{Cntrl}\\s]", "_");
    }
}
