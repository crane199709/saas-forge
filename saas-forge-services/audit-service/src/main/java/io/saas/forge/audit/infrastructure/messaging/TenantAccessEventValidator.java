package io.saas.forge.audit.infrastructure.messaging;

import io.saas.forge.audit.application.AuditRecord;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 按工程注册的 type 区分 Tenant Created 与本切片应确认忽略的合法事件。 */
@Component
public class TenantAccessEventValidator {
    private static final String TENANT_SUSPENDED_TYPE = "com.saas.forge.tenant.suspended.v1";
    private static final String PERMISSION_CATALOG_CHANGED_TYPE = "com.saas.forge.permission.catalog-changed.v1";
    private static final String ORGANIZATION_UNIT_CHANGED_TYPE = "com.saas.forge.organization.unit-changed.v1";
    private static final String ORGANIZATION_UNIT_CHANGED_SCHEMA =
            "https://saas.forge.io/contracts/events/organization-unit-changed.v1.schema.json";
    private static final String PERMISSION_CATALOG_CHANGED_SCHEMA =
            "https://saas.forge.io/contracts/events/permission-catalog-changed.v1.schema.json";
    static final String TENANT_SUSPENDED_CONSUMER_NAME = "audit-service.tenant-lifecycle-events";
    private static final String TENANT_SUSPENDED_SCHEMA =
            "https://saas.forge.io/contracts/events/tenant-suspended.v1.schema.json";
    private static final Set<String> ENVELOPE_FIELDS = Set.of(
            "specversion", "id", "source", "type", "subject", "time",
            "datacontenttype", "dataschema", "traceId", "data");
    private static final Set<String> REQUIRED_ENVELOPE_FIELDS = Set.of(
            "specversion", "id", "source", "type", "subject", "time",
            "datacontenttype", "dataschema", "data");
    private static final Set<String> TENANT_SUSPENDED_DATA_FIELDS =
            Set.of("tenantId", "actorIdentityId", "revokedSessionCount");
    private static final Set<String> PERMISSION_CATALOG_DATA_FIELDS =
            Set.of("tenantId", "code", "enabled", "source", "revision");
    private static final Set<String> ORGANIZATION_UNIT_DATA_FIELDS =
            Set.of("tenantId", "unitId", "actorIdentityId", "action", "parentId");
    private static final Pattern UUID_V7 = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");
    private static final Pattern TRACE_ID = Pattern.compile("^(?!0{32}$)[0-9a-f]{32}$");

    private final ObjectMapper objectMapper;
    private final TenantCreatedEventValidator tenantCreated;
    private final TenantDetailsUpdatedEventValidator tenantDetailsUpdated;
    private final InvitationEventValidator invitationEvents;
    private final String expectedTopic;

    public TenantAccessEventValidator(
            ObjectMapper objectMapper,
            TenantCreatedEventValidator tenantCreated,
            @Value("${saas.forge.audit.tenant-access-topic}") String expectedTopic) {
        this.objectMapper = objectMapper;
        this.tenantCreated = tenantCreated;
        this.tenantDetailsUpdated = new TenantDetailsUpdatedEventValidator(objectMapper, expectedTopic);
        this.invitationEvents = new InvitationEventValidator(objectMapper, expectedTopic);
        if (expectedTopic == null
                || !expectedTopic.matches("^saas\\.forge\\.[a-z][a-z0-9-]*\\.tenant-access-service\\.events$")) {
            throw new IllegalArgumentException("Tenant Access Topic 配置不合法");
        }
        this.expectedTopic = expectedTopic;
    }

    public Optional<AuditRecord> validate(
            String topic, String orderingKey, String consumerName, String payload) {
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(payload);
        } catch (RuntimeException exception) {
            throw new InvalidAuditEventException("Tenant Access payload 不是合法 JSON", exception);
        }
        JsonNode type = envelope == null ? null : envelope.get("type");
        if (type == null || !type.isTextual()) {
            throw invalid("type");
        }
        return switch (type.asText()) {
            case TenantCreatedEventValidator.EVENT_TYPE -> Optional.of(
                    tenantCreated.validate(topic, orderingKey, consumerName, payload));
            case TenantDetailsUpdatedEventValidator.EVENT_TYPE -> Optional.of(
                    tenantDetailsUpdated.validate(topic, orderingKey, consumerName, envelope));
            case "com.saas.forge.invitation.created.v1",
                 "com.saas.forge.invitation.resent.v1",
                 "com.saas.forge.invitation.revoked.v1" -> Optional.of(
                    invitationEvents.validate(topic, orderingKey, consumerName, envelope));
            case PERMISSION_CATALOG_CHANGED_TYPE -> Optional.of(
                    validatePermissionCatalogChanged(topic, orderingKey, consumerName, envelope));
            case ORGANIZATION_UNIT_CHANGED_TYPE -> Optional.of(
                    validateOrganizationUnitChanged(topic, orderingKey, consumerName, envelope));
            case TENANT_SUSPENDED_TYPE -> {
                validateRegisteredTenantSuspended(topic, orderingKey, consumerName, envelope);
                requireOtherRegisteredConsumer(TENANT_SUSPENDED_CONSUMER_NAME, consumerName);
                yield Optional.empty();
            }
            default -> throw new InvalidAuditEventException(
                    "Tenant Access type 未在工程注册表登记");
        };
    }

    private AuditRecord validateOrganizationUnitChanged(
            String topic, String orderingKey, String consumerName, JsonNode envelope) {
        requireObjectWithAllowedFields(envelope, ENVELOPE_FIELDS, REQUIRED_ENVELOPE_FIELDS, "Envelope");
        requireEquals("1.0", text(envelope, "specversion"), "specversion");
        requireEquals(TenantCreatedEventValidator.SOURCE, text(envelope, "source"), "source");
        requireEquals(ORGANIZATION_UNIT_CHANGED_TYPE, text(envelope, "type"), "type");
        requireEquals("application/json", text(envelope, "datacontenttype"), "datacontenttype");
        requireEquals(ORGANIZATION_UNIT_CHANGED_SCHEMA, text(envelope, "dataschema"), "dataschema");
        requireEquals(TenantCreatedEventValidator.CONSUMER_NAME, consumerName, "consumer");
        requireEquals(expectedTopic, topic, "topic");
        UUID eventId = uuidV7(text(envelope, "id"), "id");
        Instant occurredAt = utcInstant(text(envelope, "time"), "time");
        String traceId = optionalText(envelope, "traceId");
        if (traceId != null && !TRACE_ID.matcher(traceId).matches()) throw invalid("traceId");
        JsonNode data = envelope.path("data");
        requireObjectWithAllowedFields(data, ORGANIZATION_UNIT_DATA_FIELDS,
                Set.of("tenantId", "unitId", "actorIdentityId", "action"), "data");
        UUID tenantId = uuidV7(text(data, "tenantId"), "data.tenantId");
        UUID unitId = uuidV7(text(data, "unitId"), "data.unitId");
        UUID actorId = uuidV7(text(data, "actorIdentityId"), "data.actorIdentityId");
        String action = text(data, "action");
        if (!Set.of("CREATED", "RENAMED", "MOVED", "DELETED").contains(action)) throw invalid("data.action");
        JsonNode parent = data.get("parentId");
        if (parent != null && !parent.isNull()) uuidV7(parent.asText(), "data.parentId");
        requireEquals(tenantId.toString(), text(envelope, "subject"), "subject");
        requireEquals(tenantId.toString(), orderingKey, "ordering key");
        var details = new java.util.LinkedHashMap<String, Object>();
        details.put("unitId", unitId.toString());
        details.put("parentId", parent == null || parent.isNull() ? null : parent.asText());
        return new AuditRecord(eventId, TenantCreatedEventValidator.SOURCE,
                ORGANIZATION_UNIT_CHANGED_TYPE, occurredAt, traceId, actorId, tenantId,
                "ORGANIZATION_UNIT_" + action, "ORGANIZATION_UNIT", unitId,
                objectMapper.writeValueAsString(details));
    }

    private AuditRecord validatePermissionCatalogChanged(
            String topic, String orderingKey, String consumerName, JsonNode envelope) {
        requireObjectWithAllowedFields(envelope, ENVELOPE_FIELDS, REQUIRED_ENVELOPE_FIELDS, "Envelope");
        requireEquals("1.0", text(envelope, "specversion"), "specversion");
        requireEquals(TenantCreatedEventValidator.SOURCE, text(envelope, "source"), "source");
        requireEquals(PERMISSION_CATALOG_CHANGED_TYPE, text(envelope, "type"), "type");
        requireEquals("application/json", text(envelope, "datacontenttype"), "datacontenttype");
        requireEquals(PERMISSION_CATALOG_CHANGED_SCHEMA, text(envelope, "dataschema"), "dataschema");
        requireEquals(TenantCreatedEventValidator.CONSUMER_NAME, consumerName, "consumer");
        requireEquals(expectedTopic, topic, "topic");
        UUID eventId = uuidV7(text(envelope, "id"), "id");
        Instant occurredAt = utcInstant(text(envelope, "time"), "time");
        String traceId = optionalText(envelope, "traceId");
        if (traceId != null && !TRACE_ID.matcher(traceId).matches()) throw invalid("traceId");

        JsonNode data = envelope.path("data");
        requireObjectWithAllowedFields(data, PERMISSION_CATALOG_DATA_FIELDS,
                PERMISSION_CATALOG_DATA_FIELDS, "data");
        UUID tenantId = uuidV7(text(data, "tenantId"), "data.tenantId");
        String code = text(data, "code");
        String source = text(data, "source");
        JsonNode enabled = data.get("enabled");
        JsonNode revision = data.get("revision");
        if (!code.matches("^[a-z][a-z0-9-]*:[a-z][a-z0-9-]*:[a-z][a-z0-9-]*$")
                || code.length() > 160 || !source.matches("^[a-z][a-z0-9-]*$") || source.length() > 80
                || enabled == null || !enabled.isBoolean()
                || revision == null || !revision.isIntegralNumber() || revision.asInt() < 1) {
            throw invalid("data");
        }
        requireEquals(tenantId.toString(), text(envelope, "subject"), "subject");
        requireEquals(tenantId.toString(), orderingKey, "ordering key");
        return new AuditRecord(eventId, TenantCreatedEventValidator.SOURCE,
                PERMISSION_CATALOG_CHANGED_TYPE, occurredAt, traceId, null, tenantId,
                "PERMISSION_CATALOG_CHANGED", "TENANT", tenantId,
                objectMapper.writeValueAsString(Map.of("code", code, "enabled", enabled.asBoolean(),
                        "source", source, "revision", revision.asInt())));
    }

    private void validateRegisteredTenantSuspended(
            String topic, String orderingKey, String consumerName, JsonNode envelope) {
        requireObjectWithAllowedFields(envelope, ENVELOPE_FIELDS, REQUIRED_ENVELOPE_FIELDS, "Envelope");
        requireEquals("1.0", text(envelope, "specversion"), "specversion");
        requireEquals(TenantCreatedEventValidator.SOURCE, text(envelope, "source"), "source");
        requireEquals(TENANT_SUSPENDED_TYPE, text(envelope, "type"), "type");
        requireEquals("application/json", text(envelope, "datacontenttype"), "datacontenttype");
        requireEquals(TENANT_SUSPENDED_SCHEMA, text(envelope, "dataschema"), "dataschema");
        requireEquals(TenantCreatedEventValidator.CONSUMER_NAME, consumerName, "consumer");
        requireEquals(expectedTopic, topic, "topic");
        uuidV7(text(envelope, "id"), "id");
        utcInstant(text(envelope, "time"), "time");
        String traceId = optionalText(envelope, "traceId");
        if (traceId != null && !TRACE_ID.matcher(traceId).matches()) {
            throw invalid("traceId");
        }

        JsonNode data = envelope.path("data");
        requireObjectWithAllowedFields(data, TENANT_SUSPENDED_DATA_FIELDS, TENANT_SUSPENDED_DATA_FIELDS, "data");
        UUID tenantId = uuidV7(text(data, "tenantId"), "data.tenantId");
        uuidV7(text(data, "actorIdentityId"), "data.actorIdentityId");
        JsonNode revokedSessionCount = data.get("revokedSessionCount");
        if (revokedSessionCount == null
                || !revokedSessionCount.isIntegralNumber()
                || revokedSessionCount.asLong() < 0) {
            throw invalid("data.revokedSessionCount");
        }
        requireEquals(tenantId.toString(), text(envelope, "subject"), "subject");
        requireEquals(tenantId.toString(), orderingKey, "ordering key");
    }

    private void requireOtherRegisteredConsumer(String registeredConsumer, String consumerName) {
        if (registeredConsumer.equals(consumerName)) {
            throw invalid("allowed consumer");
        }
    }

    private void requireObjectWithAllowedFields(
            JsonNode node, Set<String> allowed, Set<String> required, String field) {
        if (!node.isObject()) {
            throw invalid(field);
        }
        Set<String> actual = new HashSet<>(node.propertyNames());
        if (!allowed.containsAll(actual) || !actual.containsAll(required)) {
            throw invalid(field + " fields");
        }
    }

    private String text(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw invalid(field);
        }
        return value.asText();
    }

    private String optionalText(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null) {
            return null;
        }
        if (!value.isTextual()) {
            throw invalid(field);
        }
        return value.asText();
    }

    private UUID uuidV7(String value, String field) {
        if (!UUID_V7.matcher(value).matches()) {
            throw invalid(field);
        }
        return UUID.fromString(value);
    }

    private Instant utcInstant(String value, String field) {
        if (!value.endsWith("Z")) {
            throw invalid(field);
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw new InvalidAuditEventException("Tenant Access 契约字段不合法: " + field, exception);
        }
    }

    private void requireEquals(String expected, String actual, String field) {
        if (!expected.equals(actual)) {
            throw invalid(field);
        }
    }

    private InvalidAuditEventException invalid(String field) {
        return new InvalidAuditEventException("Tenant Access 契约字段不合法: " + field);
    }
}
