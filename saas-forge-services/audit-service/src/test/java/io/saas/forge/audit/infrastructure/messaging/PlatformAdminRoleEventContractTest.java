package io.saas.forge.audit.infrastructure.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.saas.forge.audit.application.AuditRecord;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class PlatformAdminRoleEventContractTest {
    private static final String TOPIC = "saas.forge.test.iam-service.events";
    private static final String IDENTITY = "0198c9d5-0f25-7b21-8d67-31c8652d4c91";
    private static final String ACTOR = "0198c9d5-0f25-7b21-8d67-31c8652d4c8f";
    private static final String ASSIGNMENT = "0198c9d5-0f25-7b21-8d67-31c8652d4c92";
    private static final String EVENT = "0198c9d5-0f25-7b21-8d67-31c8652d4c93";
    private static final String TRACE = "1234567890abcdef1234567890abcdef";
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void acceptsOnlyCommittedRoleFactsWithAValidOptionalTrace() {
        for (String action : List.of("granted", "revoked")) {
            AuditRecord record = validate(action, TOPIC, IDENTITY,
                    SessionStartedEventValidator.CONSUMER_NAME, event(action));
            assertEquals("PLATFORM_ADMIN_" + action.toUpperCase(), record.action());
            assertEquals("PLATFORM_ROLE_ASSIGNMENT", record.resourceType());
            assertEquals(ASSIGNMENT, record.resourceId().toString());
            assertEquals(ACTOR, record.actorIdentityId().toString());
            assertEquals(TRACE, record.traceId());
            assertTrue(!record.metadata().contains("email") && !record.metadata().contains("token"));
        }
    }

    @Test
    void rejectsMisroutedOrMalformedPlatformRoleFactsBeforeAuditPersistence() {
        for (String action : List.of("granted", "revoked")) {
            String accepted = event(action);
            reject(action, "saas.forge.prod.iam-service.events", IDENTITY,
                    SessionStartedEventValidator.CONSUMER_NAME, accepted);
            reject(action, TOPIC, IDENTITY, "audit-service.other", accepted);
            reject(action, TOPIC, IDENTITY, SessionStartedEventValidator.CONSUMER_NAME, "[]");
            reject(action, TOPIC, IDENTITY, SessionStartedEventValidator.CONSUMER_NAME,
                    accepted.replace("\"traceId\":", "\"email\":\"secret@example.test\",\"traceId\":"));
            reject(action, TOPIC, IDENTITY, SessionStartedEventValidator.CONSUMER_NAME,
                    accepted.replace("\"specversion\":\"1.0\"", "\"specversion\":\"2.0\""));
            reject(action, TOPIC, IDENTITY, SessionStartedEventValidator.CONSUMER_NAME,
                    accepted.replace("urn:saas.forge:iam-service", "urn:saas.forge:other"));
            reject(action, TOPIC, IDENTITY, SessionStartedEventValidator.CONSUMER_NAME,
                    accepted.replace("com.saas.forge.iam.platform-admin-" + action + ".v1", "unregistered"));
            reject(action, TOPIC, IDENTITY, SessionStartedEventValidator.CONSUMER_NAME,
                    accepted.replace("application/json", "text/plain"));
            reject(action, TOPIC, IDENTITY, SessionStartedEventValidator.CONSUMER_NAME,
                    accepted.replace("iam-platform-admin-" + action + ".v1.schema.json", "wrong.schema.json"));
            reject(action, TOPIC, IDENTITY, SessionStartedEventValidator.CONSUMER_NAME,
                    accepted.replace("\"roleKey\":\"PLATFORM_ADMIN\"", "\"roleKey\":\"TENANT_ADMIN\""));
            reject(action, TOPIC, IDENTITY, SessionStartedEventValidator.CONSUMER_NAME,
                    accepted.replace("\"roleKey\":", "\"token\":\"secret\",\"roleKey\":"));
            reject(action, TOPIC, "0198c9d5-0f25-7b21-8d67-31c8652d4c94",
                    SessionStartedEventValidator.CONSUMER_NAME, accepted);
            reject(action, TOPIC, IDENTITY, SessionStartedEventValidator.CONSUMER_NAME,
                    accepted.replace("\"subject\":\"" + IDENTITY + "\"", "\"subject\":\"" + ACTOR + "\""));
            reject(action, TOPIC, IDENTITY, SessionStartedEventValidator.CONSUMER_NAME,
                    accepted.replace("\"occurredAt\":\"2026-10-14T08:00:00Z\"",
                            "\"occurredAt\":\"2026-10-14T08:00:01Z\""));
            reject(action, TOPIC, IDENTITY, SessionStartedEventValidator.CONSUMER_NAME,
                    accepted.replace(TRACE, "00000000000000000000000000000000"));
            reject(action, TOPIC, IDENTITY, SessionStartedEventValidator.CONSUMER_NAME,
                    accepted.replace("\"id\":\"" + EVENT + "\"", "\"id\":\"not-a-uuid\""));
            reject(action, TOPIC, IDENTITY, SessionStartedEventValidator.CONSUMER_NAME,
                    accepted.replace("\"roleAssignmentId\":\"" + ASSIGNMENT + "\"",
                            "\"roleAssignmentId\":\"not-a-uuid\""));
        }
    }

    private AuditRecord validate(String action, String topic, String key, String consumer, String payload) {
        return action.equals("granted")
                ? new PlatformAdminGrantedEventValidator(json, TOPIC).validate(topic, key, consumer, payload)
                : new PlatformAdminRevokedEventValidator(json, TOPIC).validate(topic, key, consumer, payload);
    }

    private void reject(String action, String topic, String key, String consumer, String payload) {
        assertThrows(InvalidAuditEventException.class, () -> validate(action, topic, key, consumer, payload));
    }

    private String event(String action) {
        return """
                {"specversion":"1.0","id":"%s",
                 "source":"urn:saas.forge:iam-service","type":"com.saas.forge.iam.platform-admin-%s.v1",
                 "subject":"%s","time":"2026-10-14T08:00:00Z",
                 "datacontenttype":"application/json",
                 "dataschema":"https://saas.forge.io/contracts/events/iam-platform-admin-%s.v1.schema.json",
                 "traceId":"%s",
                 "data":{"identityId":"%s","actorIdentityId":"%s",
                         "roleAssignmentId":"%s","roleKey":"PLATFORM_ADMIN",
                         "occurredAt":"2026-10-14T08:00:00Z"}}
                """.formatted(EVENT, action, IDENTITY, action, TRACE, IDENTITY, ACTOR, ASSIGNMENT);
    }
}
