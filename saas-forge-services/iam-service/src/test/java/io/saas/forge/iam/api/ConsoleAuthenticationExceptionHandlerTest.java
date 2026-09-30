package io.saas.forge.iam.api;

import static org.junit.jupiter.api.Assertions.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.read.ListAppender;
import io.saas.forge.iam.application.authentication.RevocationIndexUnavailableException;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;

class ConsoleAuthenticationExceptionHandlerTest {
    @Test
    void correlatesUnavailableLogWithProblemWithoutLoggingExceptionDetails() {
        var logger = (Logger) LoggerFactory.getLogger(ConsoleAuthenticationExceptionHandler.class);
        var appender = new ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var request = new MockHttpServletRequest();
            var response = new ConsoleAuthenticationExceptionHandler().unavailable(
                    new RevocationIndexUnavailableException(new IllegalStateException("private-details")), request);
            assertEquals(503, response.getStatusCode().value());
            assertEquals("SESSION_SECURITY_UNAVAILABLE", response.getBody().code());
            assertEquals("1", response.getHeaders().getFirst("Retry-After"));
            var log = appender.list.get(0).getFormattedMessage();
            assertTrue(log.contains("traceId=" + response.getBody().traceId()));
            assertTrue(log.contains("code=SESSION_SECURITY_UNAVAILABLE"));
            assertTrue(log.contains(RevocationIndexUnavailableException.class.getName()));
            assertFalse(log.contains("private-details"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
