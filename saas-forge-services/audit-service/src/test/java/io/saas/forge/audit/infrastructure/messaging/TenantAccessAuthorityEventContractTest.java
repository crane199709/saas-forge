package io.saas.forge.audit.infrastructure.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.saas.forge.audit.application.AuditRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class TenantAccessAuthorityEventContractTest {
    private static final String TENANT_ID = TenantCreatedEventValidatorTest.tenantId();
    private static final String TOPIC = TenantCreatedEventValidatorTest.TOPIC;
    private static final String CONSUMER = TenantCreatedEventValidator.CONSUMER_NAME;

    private final ObjectMapper mapper = new ObjectMapper();
    private final TenantAccessEventValidator validator = new TenantAccessEventValidator(
            mapper, new TenantCreatedEventValidator(mapper, TOPIC), TOPIC);

    @Test
    void roleChangeAcceptsRealActionsAndRejectsPermissionEscalationOrSecretFields() {
        String created = TenantAccessEventValidatorTest.tenantRoleChangedEvent();
        String updated = created.replace("\"CREATED\"", "\"UPDATED\"")
                .replace("\"example:project:list\"", "\"example:project:read\"");
        String deleted = created.replace("\"CREATED\"", "\"DELETED\"")
                .replace("\"example:project:list\"", "\"example:project:read\"");
        for (String valid : new String[] {created, updated, deleted}) {
            var fact = validate(valid);
            assertEquals("TENANT_ROLE_CHANGED", fact.action());
            assertEquals("TENANT_ROLE", fact.resourceType());
            assertEquals(TENANT_ID, fact.tenantId().toString());
        }
        for (String invalid : new String[] {
                created.replace("\"roleKey\":\"REPORT_READER\"", "\"roleKey\":\"reader\""),
                created.replace("\"CREATED\"", "\"ELEVATED\""),
                created.replace("\"permissions\":[\"example:project:list\"]",
                        "\"permissions\":[\"example:project:list\",\"example:project:list\"]"),
                created.replace("\"permissions\":[\"example:project:list\"]",
                        "\"permissions\":[\"example:project:list\",\"*:*:*\"]"),
                created.replace("\"permissions\":[\"example:project:list\"]",
                        "\"permissions\":\"example:project:list\""),
                created.replace("\"permissions\":", "\"password\":\"secret\",\"permissions\":"),
                created.replace("\"roleId\":\"019535d9-0001-7000-8000-000000000011\"",
                        "\"roleId\":\"019535d9-0001-4000-8000-000000000011\""),
                created.replace("\"subject\":\"" + TENANT_ID + "\"",
                        "\"subject\":\"019535d9-0001-7000-8000-000000000099\"")
        }) assertRejected(invalid);
        assertThrows(InvalidAuditEventException.class, () -> validator.validate(
                TOPIC, "019535d9-0001-7000-8000-000000000099", CONSUMER, created));
    }

    @Test
    void memberRoleChangeAcceptsAssignmentAndRevocationButRejectsCrossTenantFacts() {
        String assigned = TenantAccessEventValidatorTest.membershipRoleChangedEvent();
        assertEquals("MEMBERSHIP_ROLE_ASSIGNED", validate(assigned).action());
        assertEquals("MEMBERSHIP_ROLE_REVOKED", validate(
                assigned.replace("\"ASSIGNED\"", "\"REVOKED\"")).action());
        for (String invalid : new String[] {
                assigned.replace("\"ASSIGNED\"", "\"ELEVATED\""),
                assigned.replace("\"roleKey\":\"READER\"", "\"roleKey\":\"reader\""),
                assigned.replace("\"membershipId\":", "\"email\":\"hidden@example.test\",\"membershipId\":"),
                assigned.replace("\"actorIdentityId\":\"" + TenantCreatedEventValidatorTest.actorIdentityId() + "\"",
                        "\"actorIdentityId\":\"019535d9-0001-4000-8000-000000000099\""),
                assigned.replace("\"subject\":\"" + TENANT_ID + "\"",
                        "\"subject\":\"019535d9-0001-7000-8000-000000000099\"")
        }) assertRejected(invalid);
        assertThrows(InvalidAuditEventException.class, () -> validator.validate(
                TOPIC, "019535d9-0001-7000-8000-000000000099", CONSUMER, assigned));
    }

    @Test
    void authorityEventsRequireCorrectRouteAndOpaqueTrace() {
        String base = TenantAccessEventValidatorTest.tenantRoleChangedEvent();
        String traced = base.replace("\"data\":",
                "\"traceId\":\"1234567890abcdef1234567890abcdef\",\"data\":");
        assertEquals("1234567890abcdef1234567890abcdef", validate(traced).traceId());
        for (String invalid : new String[] {
                traced.replace("1234567890abcdef1234567890abcdef", "00000000000000000000000000000000"),
                traced.replace("\"specversion\":\"1.0\"", "\"specversion\":\"2.0\""),
                traced.replace("\"time\":\"2026-08-28T10:15:30Z\"",
                        "\"time\":\"2026-08-28T10:15:30+01:00\""),
                traced.replace("\"dataschema\":\"https://saas.forge.io/contracts/events/tenant-role-changed.v1.schema.json\"",
                        "\"dataschema\":\"https://example.test/forged\""),
                traced.replace("\"datacontenttype\":\"application/json\"",
                        "\"datacontenttype\":\"text/plain\""),
                traced.replace("\"source\":\"urn:saas.forge:tenant-access-service\"",
                        "\"source\":\"urn:saas.forge:iam-service\""),
                traced.replace("\"data\":", "\"bearerToken\":\"secret\",\"data\":")
        }) assertRejected(invalid);
        assertThrows(InvalidAuditEventException.class, () -> validator.validate(
                "saas.forge.prod.tenant-access-service.events", TENANT_ID, CONSUMER, base));
        assertThrows(InvalidAuditEventException.class, () -> validator.validate(
                TOPIC, TENANT_ID, "audit-service.other", base));
    }

    private AuditRecord validate(String payload) {
        return validator.validate(TOPIC, TENANT_ID, CONSUMER, payload).orElseThrow();
    }

    private void assertRejected(String payload) {
        assertThrows(InvalidAuditEventException.class, () -> validate(payload));
    }
}
