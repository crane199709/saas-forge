package io.saas.forge.audit.infrastructure.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class IamSessionEventValidatorTest {
    private static final String TOPIC = "saas.forge.test.iam-service.events";
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SessionStartedEventValidator sessionStarted =
            new SessionStartedEventValidator(objectMapper, TOPIC);
    private final TenantContextSwitchedEventValidator tenantContextSwitched =
            new TenantContextSwitchedEventValidator(objectMapper, TOPIC);
    private final IamSessionEventValidator validator =
            new IamSessionEventValidator(objectMapper, sessionStarted, tenantContextSwitched,
                    new PasswordRecoveredEventValidator(objectMapper, TOPIC));

    @Test
    void routesBothAuthorizedTypesToTheirStrictValidator() {
        assertEquals("SESSION_STARTED", validator.validate(
                TOPIC, TenantContextSwitchedEventValidatorTest.identityId(),
                SessionStartedEventValidator.CONSUMER_NAME,
                SessionStartedEventValidatorTest.event("")).action());
        assertEquals("TENANT_CONTEXT_SWITCHED", validator.validate(
                TOPIC, TenantContextSwitchedEventValidatorTest.identityId(),
                SessionStartedEventValidator.CONSUMER_NAME,
                TenantContextSwitchedEventValidatorTest.event(
                        "019535d9-0001-7000-8000-000000000011", "")).action());
    }

    @Test
    void rejectsMalformedOrUnauthorizedTypesThroughPermanentErrorEntry() {
        assertThrows(InvalidAuditEventException.class,
                () -> validator.validate("topic", "key", "consumer", "not-json"));
        assertThrows(InvalidAuditEventException.class,
                () -> validator.validate("topic", "key", "consumer", "{\"type\":\"other\"}"));
    }
}
