package io.saas.forge.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.common.CompletableResultCode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ForgeObservabilityTest {
    @Test void frameworkMessageMdcAndThrowableCannotLeakSecrets() throws Exception {
        var event = mock(ILoggingEvent.class);
        when(event.getTimeStamp()).thenReturn(1L); when(event.getLevel()).thenReturn(Level.ERROR);
        when(event.getLoggerName()).thenReturn("third.party");
        when(event.getThrowableProxy()).thenReturn(new ch.qos.logback.classic.spi.ThrowableProxy(new IllegalArgumentException("password=secret-value")));
        when(event.getFormattedMessage()).thenReturn("password=secret-value token=raw-value");
        when(event.getMDCPropertyMap()).thenReturn(java.util.Map.of("email", "sensitive@example.test"));
        when(event.getKeyValuePairs()).thenReturn(List.of(new org.slf4j.event.KeyValuePair("token", "raw-value")));
        var output = new ForgeLogFormatter(new MockEnvironment().withProperty("spring.application.name", "project-service")
                .withProperty("saas.forge.environment", "test")).format(event);
        assertThat(output).doesNotContain("secret-value", "raw-value", "sensitive@example.test", "third.party");
        assertThat(new ObjectMapper().readTree(output).fieldNames()).toIterable().containsExactlyInAnyOrder(
                "timestamp", "level", "service", "environment", "event", "message", "schemaVersion", "exception");
    }

    @Test void createsRealServerSpanPreservesTraceAndExportsOnlyRouteTemplate() throws Exception {
        var spans = new ArrayList<SpanData>();
        SpanExporter exporter = new SpanExporter() {
            public CompletableResultCode export(Collection<SpanData> batch) { spans.addAll(batch); return CompletableResultCode.ofSuccess(); }
            public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }
            public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
        };
        try (var provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()) {
            OpenTelemetry telemetry = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
            var filter = new HttpTraceFilter(telemetry, request -> "/api/v1/projects/{projectId}");
            var request = new MockHttpServletRequest("GET", "/api/v1/projects/private-id");
            request.addHeader("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
            request.addHeader("Authorization", "Bearer secret-value"); request.setQueryString("email=sensitive@example.test");
            filter.doFilter(request, new MockHttpServletResponse(), (input, response) -> {});
            assertThat(spans).hasSize(1);
            assertThat(spans.get(0).getTraceId()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
            assertThat(spans.get(0).getParentSpanId()).isEqualTo("00f067aa0ba902b7");
            assertThat(spans.get(0).getSpanId()).isNotEqualTo("00f067aa0ba902b7");
            assertThat(spans.get(0).getAttributes().toString()).doesNotContain("private-id", "secret-value", "sensitive@example.test");
            assertThat(request.getAttribute(HttpTraceFilter.TRACEPARENT_ATTRIBUTE).toString()).contains(spans.get(0).getSpanId());
        }
    }
}
