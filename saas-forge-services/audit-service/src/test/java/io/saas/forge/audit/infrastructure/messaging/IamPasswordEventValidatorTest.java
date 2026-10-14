package io.saas.forge.audit.infrastructure.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class IamPasswordEventValidatorTest {
    static final String TOPIC = "saas.forge.test.iam-service.events";
    static final String IDENTITY = "019535d9-0001-7000-8000-000000000002";
    static final String EVENT = "019535d9-0001-7000-8000-000000000003";
    private final IamPasswordEventValidator validator = new IamPasswordEventValidator(new ObjectMapper(), TOPIC);

    @Test
    void acceptsOnlyCommittedSetupSuccessFacts() {
        assertEquals("PASSWORD_SETUP_DELIVERED", validator.validate(TOPIC, IDENTITY,
                IamPasswordEventValidator.CONSUMER_NAME, delivered()).action());
        assertEquals("PASSWORD_ESTABLISHED", validator.validate(TOPIC, IDENTITY,
                IamPasswordEventValidator.CONSUMER_NAME, established()).action());
    }

    @Test
    void rejectsWrongConsumerAndSensitiveOrFalseSuccessData() {
        assertThrows(InvalidAuditEventException.class, () -> validator.validate(TOPIC, IDENTITY,
                SessionStartedEventValidator.CONSUMER_NAME, delivered()));
        assertThrows(InvalidAuditEventException.class, () -> validator.validate(TOPIC, IDENTITY,
                IamPasswordEventValidator.CONSUMER_NAME,
                delivered().replace("\"challengeExpiresAt\"", "\"token\":\"secret\",\"challengeExpiresAt\"")));
        assertThrows(InvalidAuditEventException.class, () -> validator.validate(TOPIC, IDENTITY,
                IamPasswordEventValidator.CONSUMER_NAME,
                established().replace("PASSWORD_ESTABLISHED", "DELIVERY_ATTEMPTED")));
    }

    static String delivered() {
        return """
                {"specversion":"1.0","id":"%s","source":"urn:saas.forge:iam-service",
                "type":"com.saas.forge.iam.password-setup-delivered.v1","subject":"%s",
                "time":"2026-08-28T10:16:00Z","datacontenttype":"application/json",
                "dataschema":"https://saas.forge.io/contracts/events/iam-password-setup-delivered.v1.schema.json",
                "data":{"identityId":"%s","deliveryRequestId":"019535d9-0001-7000-8000-000000000004",
                "challengeExpiresAt":"2026-08-28T10:26:00Z"}}
                """.formatted(EVENT, IDENTITY, IDENTITY);
    }

    static String established() {
        return """
                {"specversion":"1.0","id":"%s","source":"urn:saas.forge:iam-service",
                "type":"com.saas.forge.iam.password.established.v1","subject":"%s",
                "time":"2026-08-28T10:16:00Z","datacontenttype":"application/json",
                "dataschema":"https://saas.forge.io/contracts/events/iam-password-established.v1.schema.json",
                "data":{"identityId":"%s","credentialId":"019535d9-0001-7000-8000-000000000005",
                "result":"PASSWORD_ESTABLISHED","occurredAt":"2026-08-28T10:16:00Z"}}
                """.formatted(EVENT, IDENTITY, IDENTITY);
    }
}
