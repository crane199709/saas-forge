package io.saas.forge.audit.infrastructure.messaging;

import static org.assertj.core.api.Assertions.*;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ExampleFactEventValidatorTest {
    static final String TOPIC = "saas.forge.test.project-service.events";
    static final String PROJECT = "019535d9-0001-7000-8000-000000000007";
    static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";
    static final ObjectMapper JSON = new ObjectMapper();

    static String event(String resource, String action, String eventId) {
        return JSON.writeValueAsString(Map.ofEntries(
                Map.entry("specversion", "1.0"), Map.entry("id", eventId),
                Map.entry("source", ExampleFactEventValidator.SOURCE),
                Map.entry("type", "com.saas.forge.example." + resource + "." + action + ".v1"),
                Map.entry("subject", PROJECT), Map.entry("time", "2026-10-02T00:00:00Z"),
                Map.entry("datacontenttype", "application/json"), Map.entry("traceId", TRACE),
                Map.entry("dataschema", "https://saas.forge.io/contracts/events/example-" + resource + "-" + action + ".v1.schema.json"),
                Map.entry("data", Map.of("tenantId", PROJECT, "actorIdentityId", PROJECT, "membershipId", PROJECT,
                        "resourceId", PROJECT, "projectId", PROJECT, "version", 1))));
    }

    @Test void acceptsOnlySixReviewedFactsWithWhitelistedMetadata() {
        var validator = new ExampleFactEventValidator(JSON, TOPIC);
        for (String resource : java.util.List.of("project", "task")) {
            for (String action : java.util.List.of("created", "updated", "deleted")) {
                var record = validator.validate(TOPIC, PROJECT, ExampleFactEventValidator.CONSUMER_NAME,
                        event(resource, action, PROJECT));
                assertThat(record.action()).isEqualTo((resource + "_" + action).toUpperCase(java.util.Locale.ROOT));
                assertThat(record.traceId()).isEqualTo(TRACE);
                assertThat(JSON.readTree(record.metadata()).propertyNames()).containsExactlyInAnyOrder(
                        "membershipId", "projectId", "version", "result");
            }
        }
    }

    @Test void rejectsExtraFieldsWrongTopicWrongKeyVersionAndUnregisteredType() {
        var validator = new ExampleFactEventValidator(JSON, TOPIC);
        String valid = event("project", "created", PROJECT);
        for (String invalid : java.util.List.of(valid.replace("\"version\":1", "\"version\":0"),
                valid.replace("\"version\":1", "\"version\":1.5"),
                valid.replace("\"version\":1", "\"version\":1,\"password\":\"secret\""),
                valid.replace("\"specversion\":\"1.0\"", "\"specversion\":\"1.0\",\"token\":\"secret\""),
                valid.replace("project.created", "project.failed"), valid.replace(TRACE, "0".repeat(32)))) {
            assertThatThrownBy(() -> validator.validate(TOPIC, PROJECT, ExampleFactEventValidator.CONSUMER_NAME, invalid))
                    .isInstanceOf(InvalidAuditEventException.class);
        }
        assertThatThrownBy(() -> validator.validate("foreign", PROJECT, ExampleFactEventValidator.CONSUMER_NAME, valid))
                .isInstanceOf(InvalidAuditEventException.class);
        assertThatThrownBy(() -> validator.validate(TOPIC, "foreign", ExampleFactEventValidator.CONSUMER_NAME, valid))
                .isInstanceOf(InvalidAuditEventException.class);
    }

    @Test void doesNotAcknowledgeFailedPersistence() {
        var calls = new ArrayList<String>();
        var service = new io.saas.forge.audit.application.AuditRecordService((consumer, record, at) -> {
            calls.add("record"); throw new IllegalStateException("unavailable");
        }, Clock.systemUTC());
        var consumer = new ExampleFactKafkaConsumer(new ExampleFactEventValidator(JSON, TOPIC), service,
                io.opentelemetry.api.OpenTelemetry.noop());
        var message = new org.apache.kafka.clients.consumer.ConsumerRecord<>(TOPIC, 0, 0, PROJECT, event("task", "deleted", PROJECT));
        assertThatThrownBy(() -> consumer.consume(message, () -> calls.add("ack"))).isInstanceOf(IllegalStateException.class);
        assertThat(calls).containsExactly("record");
    }
}
