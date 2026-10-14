package io.saas.forge.audit.infrastructure.messaging;

import io.saas.forge.audit.application.AuditRecordRepository;
import io.saas.forge.audit.application.AuditRecordService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

class SessionStartedKafkaConsumerTest {
    @Test
    void acknowledgesOnlyAfterTransactionalServiceReturns() {
        List<String> calls = new ArrayList<>();
        AuditRecordRepository repository = (consumerName, actual, consumedAt) -> {
            org.junit.jupiter.api.Assertions.assertEquals("SESSION_STARTED", actual.action());
            calls.add("record");
            return true;
        };
        AuditRecordService service = new AuditRecordService(
                repository, Clock.fixed(Instant.parse("2026-08-28T10:16:00Z"), ZoneOffset.UTC));
        var objectMapper = new tools.jackson.databind.ObjectMapper();
        var validator = new IamSessionEventValidator(objectMapper,
                new SessionStartedEventValidator(objectMapper, "saas.forge.test.iam-service.events"),
                new TenantContextSwitchedEventValidator(objectMapper, "saas.forge.test.iam-service.events"),
                new PasswordRecoveredEventValidator(objectMapper, "saas.forge.test.iam-service.events"),
                new PlatformAdminGrantedEventValidator(objectMapper, "saas.forge.test.iam-service.events"),
                new PlatformAdminRevokedEventValidator(objectMapper, "saas.forge.test.iam-service.events"));
        var message = new ConsumerRecord<String, String>(
                "saas.forge.test.iam-service.events", 0, 1,
                "019535d9-0001-7000-8000-000000000002",
                SessionStartedEventValidatorTest.event(""));
        Acknowledgment acknowledgment = () -> calls.add("acknowledge");

        new IamSessionKafkaConsumer(validator, service).consume(message, acknowledgment);

        org.junit.jupiter.api.Assertions.assertEquals(List.of("record", "acknowledge"), calls);
    }
}
