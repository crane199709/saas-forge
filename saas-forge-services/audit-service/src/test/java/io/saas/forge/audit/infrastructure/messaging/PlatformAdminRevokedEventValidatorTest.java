package io.saas.forge.audit.infrastructure.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class PlatformAdminRevokedEventValidatorTest {
    private static final String TOPIC = "saas.forge.test.iam-service.events";
    private static final String IDENTITY = "0198c9d5-0f25-7b21-8d67-31c8652d4c91";
    private final PlatformAdminRevokedEventValidator validator =
            new PlatformAdminRevokedEventValidator(new ObjectMapper(), TOPIC);

    @Test
    void acceptedCommittedRoleFactBecomesPersistentAuditModelWithoutEmailOrToken() {
        var result = validator.validate(TOPIC, IDENTITY, SessionStartedEventValidator.CONSUMER_NAME, event());
        assertEquals("PLATFORM_ADMIN_REVOKED", result.action());
        assertEquals("PLATFORM_ROLE_ASSIGNMENT", result.resourceType());
        assertEquals("0198c9d5-0f25-7b21-8d67-31c8652d4c92", result.resourceId().toString());
    }

    @Test
    void rejectsUnexpectedPersonalOrCredentialFields() {
        assertThrows(InvalidAuditEventException.class, () -> validator.validate(
                TOPIC, IDENTITY, SessionStartedEventValidator.CONSUMER_NAME,
                event().replace("\"roleKey\":\"PLATFORM_ADMIN\"",
                        "\"roleKey\":\"PLATFORM_ADMIN\",\"email\":\"secret@example.test\"")));
    }

    private static String event() {
        return """
                {"specversion":"1.0","id":"0198c9d5-0f25-7b21-8d67-31c8652d4c93",
                 "source":"urn:saas.forge:iam-service","type":"com.saas.forge.iam.platform-admin-revoked.v1",
                 "subject":"0198c9d5-0f25-7b21-8d67-31c8652d4c91",
                 "time":"2026-10-14T08:00:00Z","datacontenttype":"application/json",
                 "dataschema":"https://saas.forge.io/contracts/events/iam-platform-admin-revoked.v1.schema.json",
                 "data":{"identityId":"0198c9d5-0f25-7b21-8d67-31c8652d4c91",
                         "actorIdentityId":"0198c9d5-0f25-7b21-8d67-31c8652d4c8f",
                         "roleAssignmentId":"0198c9d5-0f25-7b21-8d67-31c8652d4c92",
                         "roleKey":"PLATFORM_ADMIN","occurredAt":"2026-10-14T08:00:00Z"}}
                """;
    }
}
