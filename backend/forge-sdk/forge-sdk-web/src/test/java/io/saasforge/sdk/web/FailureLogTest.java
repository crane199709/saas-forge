package io.saasforge.sdk.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class FailureLogTest {
    private static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";
    private static final String TRACEPARENT = "00-" + TRACE_ID + "-b7ad6b7169203331-01";

    @Autowired
    MockMvc mvc;

    private final Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    private final ListAppender<ILoggingEvent> capture = new ListAppender<>() {
        @Override
        protected void append(ILoggingEvent event) {
            event.prepareForDeferredProcessing();
            super.append(event);
        }
    };

    @BeforeEach
    void captureLogs() {
        capture.start();
        root.addAppender(capture);
    }

    @AfterEach
    void stopCapture() {
        root.detachAppender(capture);
        capture.stop();
    }

    @ParameterizedTest
    @CsvSource({
        "/orders/invalid-rule,400,order.invalidRule,/orders/{id}",
        "/orders/missing,404,order.notFound,/orders/{id}",
        "/orders/abc/amount,400,request.malformed,/orders/{id}/amount",
        "/no-such-route,404,request.notFound,/**",
        "/orders/boom,500,system.error,/orders/{id}",
        "/orders/broken,500,order.storageBroken,/orders/{id}",
        "/orders/redis-down,503,system.unavailable,/orders/{id}"
    })
    void failureIsLoggedOnceWithTheResponseTrace(String path, int status, String code, String route) throws Exception {
        var response = mvc.perform(get(path).header("traceparent", TRACEPARENT))
                .andReturn()
                .getResponse();
        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(response.getContentAsString()).contains("\"traceId\":\"" + TRACE_ID + "\"");
        ILoggingEvent event = onlyFailure();
        assertThat(event.getFormattedMessage())
                .contains("method=GET", "route=" + route, "status=" + status, "code=" + code, "traceId=" + TRACE_ID);
        assertThat(event.getLevel()).isEqualTo(status == 503 ? Level.WARN : status >= 500 ? Level.ERROR : Level.INFO);
        if (status >= 500) {
            assertThat(event.getThrowableProxy()).isNotNull();
        } else {
            assertThat(event.getThrowableProxy()).isNull();
        }
    }

    @Test
    void validationSummaryDoesNotIncludeSensitiveRequestValues() throws Exception {
        mvc.perform(post("/orders?password=query-secret")
                        .header("traceparent", TRACEPARENT)
                        .header("Authorization", "Bearer credential-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"body-secret\",\"amount\":-1}"))
                .andReturn();
        ILoggingEvent event = onlyFailure();
        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        assertThat(event.getThrowableProxy()).isNull();
        assertThat(event.getFormattedMessage())
                .contains("method=POST", "route=/orders", "status=400", "code=request.invalid", TRACE_ID)
                .doesNotContain("query-secret", "credential-secret", "body-secret", "password", "title", "amount");
    }

    @Test
    void successfulResponseDoesNotProduceFailureLog() throws Exception {
        mvc.perform(get("/orders/ok").header("traceparent", TRACEPARENT)).andReturn();
        assertThat(failures()).isEmpty();
    }

    private ILoggingEvent onlyFailure() {
        assertThat(failures()).hasSize(1);
        return failures().get(0);
    }

    private List<ILoggingEvent> failures() {
        return capture.list.stream()
                .filter(event -> event.getFormattedMessage().startsWith("Request failed method="))
                .toList();
    }
}
