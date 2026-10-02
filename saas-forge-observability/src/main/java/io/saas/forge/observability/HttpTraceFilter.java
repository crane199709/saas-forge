package io.saas.forge.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.filter.OncePerRequestFilter;

/** 路由模板是唯一可导出的 HTTP 路径；调用方原始 URI、正文和凭据不进入 Span 或日志。 */
public final class HttpTraceFilter extends OncePerRequestFilter {
    public static final String TRACEPARENT_ATTRIBUTE = HttpTraceFilter.class.getName() + ".traceparent";
    public static final String CONTINUED_ATTRIBUTE = HttpTraceFilter.class.getName() + ".continued";
    private final OpenTelemetry telemetry;
    private final Function<HttpServletRequest, String> route;
    private static final TextMapGetter<HttpServletRequest> GETTER = new TextMapGetter<>() {
        public Iterable<String> keys(HttpServletRequest request) { return Collections.list(request.getHeaderNames()); }
        public String get(HttpServletRequest request, String key) {
            var values = Collections.list(request.getHeaders(key));
            return values.size() == 1 ? values.get(0) : null;
        }
    };

    public HttpTraceFilter(OpenTelemetry telemetry, Function<HttpServletRequest, String> route) {
        this.telemetry = telemetry;
        this.route = route;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String template = route.apply(request);
        Context parent = W3CTraceContextPropagator.getInstance().extract(Context.root(), request, GETTER);
        request.setAttribute(CONTINUED_ATTRIBUTE, Span.fromContext(parent).getSpanContext().isValid());
        Span span = telemetry.getTracer("saas-forge.http").spanBuilder("HTTP " + template)
                .setSpanKind(SpanKind.SERVER).setParent(parent).startSpan();
        long started = System.nanoTime();
        boolean failed = false;
        try (Scope scope = parent.with(span).makeCurrent()) {
            W3CTraceContextPropagator.getInstance().inject(Context.current(), request,
                    (carrier, key, value) -> {
                        if ("traceparent".equals(key)) carrier.setAttribute(TRACEPARENT_ATTRIBUTE, value);
                    });
            span.setAttribute("http.route", template);
            if (java.util.Set.of("DELETE", "GET", "HEAD", "OPTIONS", "PATCH", "POST", "PUT").contains(request.getMethod()))
                span.setAttribute("http.request.method", request.getMethod());
            try { chain.doFilter(request, response); }
            catch (ServletException | IOException | RuntimeException exception) {
                failed = true;
                span.setStatus(StatusCode.ERROR);
                throw exception;
            } finally {
                int status = failed ? 500 : response.getStatus();
                span.setAttribute("http.response.status_code", status);
                if (status >= 500) span.setStatus(StatusCode.ERROR);
                ForgeLogs.http(span, UUID.randomUUID().toString(), request.getMethod(), template,
                        status, (System.nanoTime() - started) / 1_000_000.0);
            }
        } finally { span.end(); }
    }
}
