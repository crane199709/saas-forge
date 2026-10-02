package io.saas.forge.observability;

import io.opentelemetry.api.trace.Span;
import java.util.Map;
import org.slf4j.LoggerFactory;

public final class ForgeLogs {
    private ForgeLogs() {}

    public static void http(Span span, String requestId, String method, String route, int status, double durationMs) {
        var builder = status >= 400 ? LoggerFactory.getLogger(ForgeLogs.class).atWarn()
                : LoggerFactory.getLogger(ForgeLogs.class).atInfo();
        builder.addKeyValue("event", status == 401 || status == 403
                ? "security.access-denied" : "http.request.completed");
        if (span.getSpanContext().isValid()) {
            builder.addKeyValue("traceId", span.getSpanContext().getTraceId());
            builder.addKeyValue("spanId", span.getSpanContext().getSpanId());
        }
        builder.addKeyValue("requestId", requestId);
        if (java.util.Set.of("DELETE", "GET", "HEAD", "OPTIONS", "PATCH", "POST", "PUT").contains(method))
            builder.addKeyValue("http", Map.of("method", method, "route", route,
                    "statusCode", status, "durationMs", durationMs));
        builder.log("HTTP request completed");
    }
    public static void fact(Span span, String event) {
        if (!java.util.Set.of("example.fact.published", "example.fact.retry", "audit.record.appended", "audit.record.duplicate").contains(event))
            throw new IllegalArgumentException("未登记的日志事件");
        var builder = event.equals("example.fact.retry") ? LoggerFactory.getLogger(ForgeLogs.class).atWarn()
                : LoggerFactory.getLogger(ForgeLogs.class).atInfo();
        builder.addKeyValue("event", event);
        if (span.getSpanContext().isValid()) {
            builder.addKeyValue("traceId", span.getSpanContext().getTraceId());
            builder.addKeyValue("spanId", span.getSpanContext().getSpanId());
        }
        builder.log("Committed fact delivery");
    }
}
