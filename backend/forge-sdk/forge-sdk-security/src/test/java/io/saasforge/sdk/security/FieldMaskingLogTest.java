package io.saasforge.sdk.security;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.AsyncAppender;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.pattern.KeyValuePairConverter;
import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.util.LogbackMDCAdapter;
import ch.qos.logback.core.OutputStreamAppender;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.mock.env.MockEnvironment;

/** 验证真正编码到输出流的文本，覆盖启动时运行时尚未就绪的窗口。 */
class FieldMaskingLogTest {
    record Person(
            @SensitiveField(path = "person.phone", grade = "L3")
            String phone) {}

    @Test
    void startupLogDropsDeclaredNamedValuesBeforeIamIsReady() {
        var loader = new ClassLoader(getClass().getClassLoader()) {
            @Override
            public InputStream getResourceAsStream(String name) {
                if ("META-INF/forge/manifest.json".equals(name)) {
                    return new ByteArrayInputStream(
                            "{\"sensitiveFields\":[{\"path\":\"manual.secret\",\"grade\":\"L3\"}]}"
                                    .getBytes(StandardCharsets.UTF_8));
                }
                return super.getResourceAsStream(name);
            }
        };
        var app = new SpringApplication(new DefaultResourceLoader(loader), FieldMaskingLogTest.class);
        app.setMainApplicationClass(FieldMaskingLogTest.class);
        new FieldMaskingLoggingEnvironmentPostProcessor().postProcessEnvironment(new MockEnvironment(), app);
        var context = new LoggerContext();
        context.setMDCAdapter(new LogbackMDCAdapter());
        context.start();
        try {
            var bytes = new ByteArrayOutputStream();
            var encoder = new PatternLayoutEncoder();
            encoder.setContext(context);
            encoder.setPattern("%m|%msg|%message|%kvp%n");
            encoder.start();
            var appender = new OutputStreamAppender<ILoggingEvent>();
            appender.setContext(context);
            appender.setEncoder(encoder);
            appender.setOutputStream(bytes);
            appender.start();
            var logger = context.getLogger("test");
            logger.addAppender(appender);
            logger.atInfo()
                    .addKeyValue("phone", "13812345678")
                    .addKeyValue("marked", SensitiveLogValue.of(null, "13812345678"))
                    .log("phone={} manual.secret={} order={}", "13812345678", "manual-raw-secret", "ORD-1");
            String text = bytes.toString(StandardCharsets.UTF_8);
            assertThat(text).doesNotContain("13812345678", "manual-raw-secret").contains("[redacted]", "ORD-1");
        } finally {
            context.stop();
        }
    }

    @Test
    void explicitLogWrapperNeverExposesRawTextWithoutAConverter() {
        assertThat(SensitiveLogValue.of("person.phone", "13812345678").toString())
                .isEqualTo("[redacted]");
    }

    @Configuration(proxyBeanMethods = false)
    @Import({FieldMaskingAutoConfiguration.class, FieldMaskingLoggingAutoConfiguration.class})
    static class LogApplication {}

    @Test
    void bootActivatesMaskingAndAsyncEncoderUsesCapturedTenantAndDropsExpiredPolicies() throws Exception {
        // 模拟新进程的 Logback 初始状态，避免另一测试手工注册过 converter 掩盖 Boot SPI 漏接。
        PatternLayout.DEFAULT_CONVERTER_SUPPLIER_MAP.put("m", MessageConverter::new);
        PatternLayout.DEFAULT_CONVERTER_SUPPLIER_MAP.put("msg", MessageConverter::new);
        PatternLayout.DEFAULT_CONVERTER_SUPPLIER_MAP.put("message", MessageConverter::new);
        PatternLayout.DEFAULT_CONVERTER_SUPPLIER_MAP.put("kvp", KeyValuePairConverter::new);
        var body = new AtomicReference<>("""
                {"code":"ok","httpStatus":200,"data":{"person.phone":{"path":"person.phone","grade":"L3","rule":{"strategy":"phone"},"exportAllowed":true}}}
                """);
        var requests = new AtomicInteger();
        var block = new AtomicReference<CountDownLatch>();
        var iam = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String tenant = "019a0110-0000-7000-8000-000000000001";
        iam.createContext("/internal/field-masking/" + tenant, exchange -> {
            requests.incrementAndGet();
            try {
                var latch = block.get();
                if (latch != null) latch.await(5, TimeUnit.SECONDS);
                byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(bytes);
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        });
        iam.start();
        var app = new SpringApplication(LogApplication.class);
        app.setMainApplicationClass(FieldMaskingLogTest.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        var context = new LoggerContext();
        context.setMDCAdapter(new LogbackMDCAdapter());
        context.start();
        var release = new CountDownLatch(1);
        try (var application = app.run(
                "--forge.field-masking.enabled=true",
                "--forge.field-masking.iam-uri=http://127.0.0.1:"
                        + iam.getAddress().getPort(),
                "--forge.field-masking.token=log-test-token")) {
            var bytes = new ByteArrayOutputStream();
            var encoder = new PatternLayoutEncoder();
            encoder.setContext(context);
            encoder.setPattern("%msg %kvp%n");
            encoder.start();
            var output = new OutputStreamAppender<ILoggingEvent>();
            output.setContext(context);
            output.setEncoder(encoder);
            output.setOutputStream(bytes);
            output.start();
            var async = new AsyncAppender();
            async.setContext(context);
            async.addAppender(output);
            async.start();
            var logger = context.getLogger("async-test");
            logger.addAppender(async);
            context.getMDCAdapter().put("tenant_id", tenant);
            logger.info("phone={} order={}", "13812345678", "ORD-FIRST");
            context.getMDCAdapter().clear();
            for (int i = 0; i < 100 && !bytes.toString(StandardCharsets.UTF_8).contains("ORD-FIRST"); i++)
                Thread.sleep(10);
            assertThat(bytes.toString(StandardCharsets.UTF_8))
                    .contains("[redacted]")
                    .doesNotContain("13812345678");
            for (int i = 0; i < 100; i++) {
                context.getMDCAdapter().put("tenant_id", tenant);
                logger.atInfo()
                        .addKeyValue("phone", "13812345678")
                        .log(
                                "person.phone={} marked={} order={}",
                                "13812345678",
                                SensitiveLogValue.of("person.phone", "13812345678"),
                                "ORD-READY");
                context.getMDCAdapter().clear();
                Thread.sleep(10);
                if (bytes.toString(StandardCharsets.UTF_8).contains("138****5678")) break;
            }
            assertThat(bytes.toString(StandardCharsets.UTF_8))
                    .contains("138****5678", "ORD-READY")
                    .doesNotContain("13812345678");
            block.set(release);
            context.getMDCAdapter().put("tenant_id", tenant);
            logger.info("phone={}", "13812345678");
            for (int i = 0; i < 100 && requests.get() < 2; i++) Thread.sleep(10);
            Thread.sleep(1100);
            bytes.reset();
            long before = System.nanoTime();
            logger.info("phone={} order={}", "13812345678", "ORD-EXPIRED");
            assertThat(Duration.ofNanos(System.nanoTime() - before)).isLessThan(Duration.ofMillis(500));
            context.getMDCAdapter().clear();
            for (int i = 0; i < 100 && !bytes.toString(StandardCharsets.UTF_8).contains("ORD-EXPIRED"); i++)
                Thread.sleep(10);
            assertThat(bytes.toString(StandardCharsets.UTF_8))
                    .contains("[redacted]", "ORD-EXPIRED")
                    .doesNotContain("13812345678", "138****5678");
            body.set(
                    "{\"code\":\"ok\",\"data\":{\"person.phone\":{\"path\":\"person.phone\",\"grade\":\"L3\",\"exportAllowed\":true}}}");
            release.countDown();
            Thread.sleep(1100);
            bytes.reset();
            context.getMDCAdapter().put("tenant_id", tenant);
            logger.atInfo().addKeyValue("phone", "13812345678").log("phone={} order={}", "13812345678", "ORD-MISSING");
            context.getMDCAdapter().clear();
            for (int i = 0; i < 100 && !bytes.toString(StandardCharsets.UTF_8).contains("ORD-MISSING"); i++)
                Thread.sleep(10);
            assertThat(bytes.toString(StandardCharsets.UTF_8))
                    .contains("[redacted]", "ORD-MISSING")
                    .doesNotContain("13812345678", "138****5678");
            assertThat(context.getStatusManager().getCopyOfStatusList())
                    .anySatisfy(status -> assertThat(status.getMessage()).contains("discarded"));
            iam.stop(0);
            Thread.sleep(1100);
            bytes.reset();
            context.getMDCAdapter().put("tenant_id", tenant);
            logger.info("phone={} order={}", "13812345678", "ORD-OFFLINE");
            context.getMDCAdapter().clear();
            for (int i = 0; i < 100 && !bytes.toString(StandardCharsets.UTF_8).contains("ORD-OFFLINE"); i++)
                Thread.sleep(10);
            assertThat(bytes.toString(StandardCharsets.UTF_8))
                    .contains("[redacted]", "ORD-OFFLINE")
                    .doesNotContain("13812345678");
            application.close();
            bytes.reset();
            logger.info("phone={} order={}", "13812345678", "ORD-CLOSED");
            async.stop();
            assertThat(bytes.toString(StandardCharsets.UTF_8))
                    .contains("[redacted]", "ORD-CLOSED")
                    .doesNotContain("13812345678");
        } finally {
            release.countDown();
            context.stop();
            iam.stop(0);
        }
        // 容器关闭后 converter 保持安全，不保留已关闭应用的策略提供方。
        assertThat(SensitiveLogValue.of("person.phone", "13812345678").toString())
                .isEqualTo("[redacted]");
    }
}
