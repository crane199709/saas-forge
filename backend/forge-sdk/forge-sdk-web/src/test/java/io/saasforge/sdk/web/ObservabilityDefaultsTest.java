package io.saasforge.sdk.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.registry.otlp.OtlpMeterRegistry;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/** 未配置采集端时不推送：本期不搭建采集后端，默认推送只会反复连接失败。 */
@SpringBootTest
class ObservabilityDefaultsTest {

    @Autowired
    ApplicationContext context;

    @Test
    void nothingIsExportedUntilCollectorIsConfigured() {
        assertThat(context.getBeansOfType(OtlpMeterRegistry.class)).isEmpty();
        assertThat(context.getBeansOfType(SpanExporter.class)).isEmpty();
    }
}
