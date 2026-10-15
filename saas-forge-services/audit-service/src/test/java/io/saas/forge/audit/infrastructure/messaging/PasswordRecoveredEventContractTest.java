package io.saas.forge.audit.infrastructure.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.saas.forge.audit.application.AuditRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class PasswordRecoveredEventContractTest {
    private static final String TOPIC = "saas.forge.test.iam-service.events";
    private static final String IDENTITY = "019535d9-0001-7000-8000-000000000091";
    private static final String EVENT = "019535d9-0001-7000-8000-000000000092";
    private static final String CHALLENGE = "019535d9-0001-7000-8000-000000000093";
    private static final String CREDENTIAL = "019535d9-0001-7000-8000-000000000094";
    private final PasswordRecoveredEventValidator validator =
            new PasswordRecoveredEventValidator(new ObjectMapper(), TOPIC);

    @Test
    void committedRecoveryPersistsOnlyOpaqueIdentifiers() {
        AuditRecord record = accept(TOPIC, IDENTITY, SessionStartedEventValidator.CONSUMER_NAME, event());
        assertEquals("PASSWORD_RECOVERED", record.action());
        assertEquals(IDENTITY, record.resourceId().toString());
        assertEquals(IDENTITY, record.actorIdentityId().toString());
        assertEquals("IDENTITY", record.resourceType());
        assertFalse(record.metadata().contains("password"));
        assertFalse(record.metadata().contains("token"));
    }

    @Test
    void rejectsCrossIdentityAndCredentialBearingRecoveryFacts() {
        String accepted = event();
        reject("saas.forge.prod.iam-service.events", IDENTITY,
                SessionStartedEventValidator.CONSUMER_NAME, accepted);
        reject(TOPIC, IDENTITY, "audit-service.other", accepted);
        reject(TOPIC, CREDENTIAL, SessionStartedEventValidator.CONSUMER_NAME, accepted);
        reject(TOPIC, IDENTITY, SessionStartedEventValidator.CONSUMER_NAME,
                accepted.replace("\"subject\":\"" + IDENTITY + "\"",
                        "\"subject\":\"" + CREDENTIAL + "\""));
        reject(TOPIC, IDENTITY, SessionStartedEventValidator.CONSUMER_NAME,
                accepted.replace("\"challengeId\":", "\"recoveryToken\":\"secret\",\"challengeId\":"));
        reject(TOPIC, IDENTITY, SessionStartedEventValidator.CONSUMER_NAME,
                accepted.replace("\"credentialId\":", "\"newPassword\":\"secret\",\"credentialId\":"));
        reject(TOPIC, IDENTITY, SessionStartedEventValidator.CONSUMER_NAME,
                accepted.replace("\"id\":\"" + EVENT + "\"", "\"id\":\"not-a-uuid\""));
        reject(TOPIC, IDENTITY, SessionStartedEventValidator.CONSUMER_NAME,
                accepted.replace("\"challengeId\":\"" + CHALLENGE + "\"",
                        "\"challengeId\":\"not-a-uuid\""));
        reject(TOPIC, IDENTITY, SessionStartedEventValidator.CONSUMER_NAME,
                accepted.replace("\"occurredAt\":\"2026-10-14T08:00:00Z\"",
                        "\"occurredAt\":\"2026-10-14T08:00:01Z\""));
        reject(TOPIC, IDENTITY, SessionStartedEventValidator.CONSUMER_NAME,
                accepted.replace("2026-10-14T08:00:00Z", "2026-10-14T08:00:00+01:00"));
        reject(TOPIC, IDENTITY, SessionStartedEventValidator.CONSUMER_NAME, "[]");
    }

    private AuditRecord accept(String topic, String key, String consumer, String payload) {
        return validator.validate(topic, key, consumer, payload);
    }

    private void reject(String topic, String key, String consumer, String payload) {
        assertThrows(InvalidAuditEventException.class, () -> accept(topic, key, consumer, payload));
    }

    private String event() {
        return """
                {"specversion":"1.0","id":"%s","source":"urn:saas.forge:iam-service",
                "type":"com.saas.forge.iam.password.recovered.v1","subject":"%s",
                "time":"2026-10-14T08:00:00Z","datacontenttype":"application/json",
                "dataschema":"https://saas.forge.io/contracts/events/iam-password-recovered.v1.schema.json",
                "data":{"identityId":"%s","challengeId":"%s","credentialId":"%s",
                "occurredAt":"2026-10-14T08:00:00Z"}}
                """.formatted(EVENT, IDENTITY, IDENTITY, CHALLENGE, CREDENTIAL);
    }
}
