package io.saasforge.sdk.core.api;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class FailureLogsTest {
    private final Logger log = (Logger) LoggerFactory.getLogger(FailureLogsTest.class);
    private final ListAppender<ILoggingEvent> capture = new ListAppender<>();

    @BeforeEach
    void captureLogs() {
        capture.start();
        log.addAppender(capture);
    }

    @AfterEach
    void stopCapture() {
        log.detachAppender(capture);
        capture.stop();
    }

    @Test
    void clientFailureStaysOnOneLineWithoutQueryDetailsOrStack() {
        ApiResponse<Void> body = ApiResponse.error(400, "request.invalid", "private-error", "private-tip", null)
                .withTraceId("trace-fixture");
        FailureLogs.write(
                log,
                org.slf4j.event.Level.ERROR,
                "GET",
                "/safe\r\npath?token=query-secret",
                body,
                new IllegalArgumentException("private-stack"));
        assertThat(capture.list).hasSize(1);
        ILoggingEvent event = capture.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        assertThat(event.getThrowableProxy()).isNull();
        assertThat(event.getFormattedMessage())
                .contains("route=/safe__path", "traceId=trace-fixture")
                .doesNotContain("\r", "\n", "token", "query-secret", "private-error", "private-tip", "private-stack");
    }

    @Test
    void serverFailurePreservesTheRequestedLevelAndStack() {
        var cause = new IllegalStateException("resource failure");
        FailureLogs.write(
                log,
                org.slf4j.event.Level.WARN,
                "GET",
                "/resource",
                ApiResponse.error(503, "system.unavailable", "Internal error", "稍后重试", null),
                cause);
        assertThat(capture.list).hasSize(1);
        ILoggingEvent event = capture.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getThrowableProxy().getClassName()).isEqualTo(IllegalStateException.class.getName());
    }

    @Test
    void unknownPathLengthIsBoundedAndSuccessDoesNotLog() {
        FailureLogs.write(
                log,
                org.slf4j.event.Level.ERROR,
                "GET",
                "/" + "a".repeat(10000),
                ApiResponse.error(404, "request.notFound", "missing", "不存在", null),
                null);
        assertThat(capture.list.get(0).getFormattedMessage().length()).isLessThan(700);
        capture.list.clear();
        FailureLogs.write(log, org.slf4j.event.Level.ERROR, "GET", "/ok", ApiResponse.ok(), null);
        assertThat(capture.list).isEmpty();
    }
}
