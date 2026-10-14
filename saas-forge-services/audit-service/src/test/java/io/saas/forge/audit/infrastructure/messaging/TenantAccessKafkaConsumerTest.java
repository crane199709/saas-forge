package io.saas.forge.audit.infrastructure.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import tools.jackson.databind.ObjectMapper;

class TenantAccessKafkaConsumerTest {
    @Test
    void recordsTenantCreatedBeforeAcknowledgment() {
        List<String> calls = new ArrayList<>();
        var consumer = consumer((consumerName, record, consumedAt) -> {
            assertEquals(TenantCreatedEventValidator.CONSUMER_NAME, consumerName);
            assertEquals("TENANT_CREATED", record.action());
            calls.add("record");
            return true;
        }, new SimpleMeterRegistry());

        consumer.consume(message(TenantCreatedEventValidatorTest.event(
                "019535d9-0001-7000-8000-000000000001", "")), () -> calls.add("acknowledge"));

        assertEquals(List.of("record", "acknowledge"), calls);
    }

    @Test
    void recordsTenantClosedBeforeAcknowledgment() {
        List<String> calls = new ArrayList<>();
        var consumer = consumer((consumerName, record, consumedAt) -> {
            assertEquals(TenantCreatedEventValidator.CONSUMER_NAME, consumerName);
            assertEquals("TENANT_CLOSED", record.action());
            calls.add("record");
            return true;
        }, new SimpleMeterRegistry());

        consumer.consume(message(TenantAccessEventValidatorTest.registeredTenantClosedEvent()),
                () -> calls.add("acknowledge"));
        assertEquals(List.of("record", "acknowledge"), calls);
    }

    @Test
    void acknowledgesTypeRegisteredToOtherConsumerAndCountsIgnoredWithoutPersistence() {
        List<String> calls = new ArrayList<>();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        var consumer = consumer((consumerName, record, consumedAt) -> {
            calls.add("unexpected-record");
            return true;
        }, meters);

        consumer.consume(message(TenantAccessEventValidatorTest.registeredTenantSuspendedEvent()),
                () -> calls.add("acknowledge"));

        assertEquals(List.of("acknowledge"), calls);
        assertEquals(1.0, meters.get("saas.forge.audit.consumer.events")
                .tag("consumer", TenantCreatedEventValidator.CONSUMER_NAME)
                .tag("result", "ignored")
                .counter().count());
    }

    private static TenantAccessKafkaConsumer consumer(
            AuditRecordRepository repository, SimpleMeterRegistry meters) {
        ObjectMapper objectMapper = new ObjectMapper();
        var validator = new TenantAccessEventValidator(
                objectMapper,
                new TenantCreatedEventValidator(objectMapper, TenantCreatedEventValidatorTest.TOPIC),
                TenantCreatedEventValidatorTest.TOPIC);
        var service = new AuditRecordService(repository,
                Clock.fixed(Instant.parse("2026-08-28T10:16:00Z"), ZoneOffset.UTC));
        return new TenantAccessKafkaConsumer(validator, service, meters);
    }

    private static ConsumerRecord<String, String> message(String payload) {
        return new ConsumerRecord<>(
                TenantCreatedEventValidatorTest.TOPIC, 0, 1,
                TenantCreatedEventValidatorTest.tenantId(), payload);
    }
}
