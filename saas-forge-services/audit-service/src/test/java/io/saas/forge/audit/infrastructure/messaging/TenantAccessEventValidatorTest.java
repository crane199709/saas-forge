package io.saas.forge.audit.infrastructure.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class TenantAccessEventValidatorTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TenantAccessEventValidator validator = new TenantAccessEventValidator(
            objectMapper,
            new TenantCreatedEventValidator(objectMapper, TenantCreatedEventValidatorTest.TOPIC),
            TenantCreatedEventValidatorTest.TOPIC);

    @Test
    void routesTenantCreatedToItsStrictValidator() {
        var result = validator.validate(
                TenantCreatedEventValidatorTest.TOPIC,
                TenantCreatedEventValidatorTest.tenantId(),
                TenantCreatedEventValidator.CONSUMER_NAME,
                TenantCreatedEventValidatorTest.event("019535d9-0001-7000-8000-000000000001", ""));

        assertEquals("TENANT_CREATED", result.orElseThrow().action());
    }

    @Test
    void tenantDetailsUpdateProducesACommitAuditRecord() {
        var result = validator.validate(
                TenantCreatedEventValidatorTest.TOPIC,
                TenantCreatedEventValidatorTest.tenantId(),
                TenantCreatedEventValidator.CONSUMER_NAME,
                tenantDetailsUpdatedEvent());

        assertEquals("TENANT_DETAILS_UPDATED", result.orElseThrow().action());
        assertEquals(TenantCreatedEventValidatorTest.tenantId(), result.orElseThrow().tenantId().toString());
        assertTrue(result.orElseThrow().metadata().contains("New name"));
    }

    static String tenantDetailsUpdatedEvent() {
        return """
                {"specversion":"1.0","id":"019535d9-0001-7000-8000-000000000009",
                "source":"urn:saas.forge:tenant-access-service","type":"com.saas.forge.tenant.details-updated.v1",
                "subject":"%s","time":"2026-08-28T10:15:30Z",
                "datacontenttype":"application/json",
                "dataschema":"https://saas.forge.io/contracts/events/tenant-details-updated.v1.schema.json",
                "data":{"tenantId":"%s","actorIdentityId":"%s","displayName":"New name","expiresAt":null}}
                """.formatted(TenantCreatedEventValidatorTest.tenantId(),
                        TenantCreatedEventValidatorTest.tenantId(),
                        TenantCreatedEventValidatorTest.actorIdentityId()).replace("\n", "");
    }

    @Test
    void ignoresRegisteredTenantTypeBelongingToOtherConsumer() {
        assertEquals("audit-service.tenant-lifecycle-events",
                TenantAccessEventValidator.TENANT_SUSPENDED_CONSUMER_NAME);
        var result = validator.validate(
                TenantCreatedEventValidatorTest.TOPIC,
                TenantCreatedEventValidatorTest.tenantId(),
                TenantCreatedEventValidator.CONSUMER_NAME,
                registeredTenantSuspendedEvent());

        assertTrue(result.isEmpty());
    }

    @Test
    void recordsPermissionCatalogChangeAndRejectsMalformedChange() {
        String event = """
                {"specversion":"1.0","id":"019535d9-0001-7000-8000-000000000009",
                "source":"urn:saas.forge:tenant-access-service","type":"com.saas.forge.permission.catalog-changed.v1",
                "subject":"%s","time":"2026-08-28T10:15:30Z",
                "datacontenttype":"application/json",
                "dataschema":"https://saas.forge.io/contracts/events/permission-catalog-changed.v1.schema.json",
                "data":{"tenantId":"%s","code":"example:project:list","enabled":false,
                "source":"example-service","revision":2}}
                """.formatted(TenantCreatedEventValidatorTest.tenantId(),
                TenantCreatedEventValidatorTest.tenantId()).replace("\n", "");

        var record = validator.validate(TenantCreatedEventValidatorTest.TOPIC,
                TenantCreatedEventValidatorTest.tenantId(),
                TenantCreatedEventValidator.CONSUMER_NAME, event).orElseThrow();
        assertEquals("PERMISSION_CATALOG_CHANGED", record.action());
        assertEquals("TENANT", record.resourceType());
        assertEquals(TenantCreatedEventValidatorTest.tenantId(), record.resourceId().toString());
        assertTrue(record.metadata().contains("\"enabled\":false"));
        assertThrows(InvalidAuditEventException.class, () -> validator.validate(
                TenantCreatedEventValidatorTest.TOPIC, TenantCreatedEventValidatorTest.tenantId(),
                TenantCreatedEventValidator.CONSUMER_NAME,
                event.replace("\"revision\":2", "\"revision\":0")));
    }

    @Test
    void roleChangeProducesPersistentAuditMetadataAndRejectsInvalidPermission() {
        String event = tenantRoleChangedEvent();
        var record = validator.validate(TenantCreatedEventValidatorTest.TOPIC,
                TenantCreatedEventValidatorTest.tenantId(), TenantCreatedEventValidator.CONSUMER_NAME,
                event).orElseThrow();
        assertEquals("TENANT_ROLE_CHANGED", record.action());
        assertEquals("TENANT_ROLE", record.resourceType());
        assertTrue(record.metadata().contains("example:project:list"));
        assertThrows(InvalidAuditEventException.class, () -> validator.validate(
                TenantCreatedEventValidatorTest.TOPIC, TenantCreatedEventValidatorTest.tenantId(),
                TenantCreatedEventValidator.CONSUMER_NAME,
                event.replace("example:project:list", "invalid")));
    }

    static String tenantRoleChangedEvent() {
        return """
                {"specversion":"1.0","id":"019535d9-0001-7000-8000-000000000010",
                "source":"urn:saas.forge:tenant-access-service","type":"com.saas.forge.tenant-role.changed.v1",
                "subject":"%s","time":"2026-08-28T10:15:30Z",
                "datacontenttype":"application/json",
                "dataschema":"https://saas.forge.io/contracts/events/tenant-role-changed.v1.schema.json",
                "data":{"tenantId":"%s","actorIdentityId":"%s",
                "roleId":"019535d9-0001-7000-8000-000000000011","roleKey":"REPORT_READER",
                "action":"CREATED","permissions":["example:project:list"]}}
                """.formatted(TenantCreatedEventValidatorTest.tenantId(),
                TenantCreatedEventValidatorTest.tenantId(),
                TenantCreatedEventValidatorTest.actorIdentityId()).replace("\n", "");
    }

    @Test
    void rejectsUnregisteredTypeWrongSourceTopicOrConsumerThroughPermanentErrorEntry() {
        String registered = registeredTenantSuspendedEvent();
        assertThrows(InvalidAuditEventException.class, () -> validator.validate(
                TenantCreatedEventValidatorTest.TOPIC,
                TenantCreatedEventValidatorTest.tenantId(),
                TenantCreatedEventValidator.CONSUMER_NAME,
                registered.replace("com.saas.forge.tenant.suspended.v1", "com.saas.forge.tenant.other.v1")));
        assertThrows(InvalidAuditEventException.class, () -> validator.validate(
                TenantCreatedEventValidatorTest.TOPIC,
                TenantCreatedEventValidatorTest.tenantId(),
                TenantCreatedEventValidator.CONSUMER_NAME,
                registered.replace("urn:saas.forge:tenant-access-service", "urn:saas.forge:other")));
        assertThrows(InvalidAuditEventException.class, () -> validator.validate(
                "saas.forge.prod.tenant-access-service.events",
                TenantCreatedEventValidatorTest.tenantId(),
                TenantCreatedEventValidator.CONSUMER_NAME,
                registered));
        assertThrows(InvalidAuditEventException.class, () -> validator.validate(
                TenantCreatedEventValidatorTest.TOPIC,
                TenantCreatedEventValidatorTest.tenantId(),
                "audit-service.other",
                registered));
    }

    static String registeredTenantSuspendedEvent() {
        return """
                {"specversion":"1.0","id":"019535d9-0001-7000-8000-000000000008",
                "source":"urn:saas.forge:tenant-access-service","type":"com.saas.forge.tenant.suspended.v1",
                "subject":"%s","time":"2026-08-28T10:15:30Z",
                "datacontenttype":"application/json",
                "dataschema":"https://saas.forge.io/contracts/events/tenant-suspended.v1.schema.json",
                "data":{"tenantId":"%s","actorIdentityId":"%s","revokedSessionCount":2}}
                """.formatted(
                        TenantCreatedEventValidatorTest.tenantId(),
                        TenantCreatedEventValidatorTest.tenantId(),
                        TenantCreatedEventValidatorTest.actorIdentityId()).replace("\n", "");
    }
}
