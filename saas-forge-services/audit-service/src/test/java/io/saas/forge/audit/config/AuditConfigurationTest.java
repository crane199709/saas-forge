package io.saas.forge.audit.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.saas.forge.audit.application.AuditConsumerFailurePolicy;
import io.saas.forge.audit.infrastructure.messaging.AuditConsumerFailureHandler;
import io.saas.forge.audit.infrastructure.messaging.InvalidAuditEventException;
import java.time.Duration;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

class AuditConfigurationTest {
    @Test
    void retriesTransientFailureUpToTenTotalAttemptsBeforeRecovery() {
        var failures = new CountingFailureHandler();
        var policy = new AuditConsumerFailurePolicy(10, Duration.ofMillis(1), Duration.ofMillis(2));
        var handler = new AuditConfiguration().auditKafkaErrorHandler(failures, policy);
        var message = new ConsumerRecord<String, String>("input", 0, 1, "key", "value");

        for (int attempt = 1; attempt < 10; attempt++) {
            assertFalse(handler.handleOne(
                    new IllegalStateException("transient"), message, null, null));
        }
        assertTrue(handler.handleOne(
                new IllegalStateException("transient"), message, null, null));

        assertTrue(failures.recovered);
        assertEquals(10, failures.failureCount);
    }

    @Test
    void routesPermanentValidationFailureDirectlyToRecovery() {
        var failures = new CountingFailureHandler();
        var policy = new AuditConsumerFailurePolicy(10, Duration.ofMillis(1), Duration.ofMillis(2));
        var handler = new AuditConfiguration().auditKafkaErrorHandler(failures, policy);
        var message = new ConsumerRecord<String, String>("input", 0, 2, "key", "value");

        assertTrue(handler.handleOne(
                new InvalidAuditEventException("invalid"), message, null, null));

        assertTrue(failures.recovered);
        assertEquals(1, failures.failureCount);
    }

    private static final class CountingFailureHandler extends AuditConsumerFailureHandler {
        private int failureCount;
        private boolean recovered;

        private CountingFailureHandler() {
            super(null, null, null, null, null, null, null);
        }

        @Override
        public void recordFailureWithoutInterruptingRetry(
                ConsumerRecord<?, ?> message, Exception exception, int attemptCount) {
            failureCount++;
        }

        @Override
        public void isolate(ConsumerRecord<?, ?> message, Exception exception) {
            recovered = true;
        }
    }
}
