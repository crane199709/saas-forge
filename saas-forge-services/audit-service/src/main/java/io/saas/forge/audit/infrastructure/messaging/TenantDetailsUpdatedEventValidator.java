package io.saas.forge.audit.infrastructure.messaging;

import io.saas.forge.audit.application.AuditRecord;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class TenantDetailsUpdatedEventValidator {
    static final String EVENT_TYPE = "com.saas.forge.tenant.details-updated.v1";
    private static final String DATASCHEMA =
            "https://saas.forge.io/contracts/events/tenant-details-updated.v1.schema.json";
    private static final Set<String> ENVELOPE_FIELDS = Set.of(
            "specversion", "id", "source", "type", "subject", "time",
            "datacontenttype", "dataschema", "traceId", "data");
    private static final Set<String> REQUIRED_ENVELOPE_FIELDS = Set.of(
            "specversion", "id", "source", "type", "subject", "time",
            "datacontenttype", "dataschema", "data");
    private static final Set<String> DATA_FIELDS =
            Set.of("tenantId", "actorIdentityId", "displayName", "expiresAt");
    private static final Pattern UUID_V7 = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");
    private static final Pattern TRACE_ID = Pattern.compile("^(?!0{32}$)[0-9a-f]{32}$");
    private final ObjectMapper objectMapper;
    private final String expectedTopic;

    TenantDetailsUpdatedEventValidator(ObjectMapper objectMapper, String expectedTopic) {
        this.objectMapper = objectMapper;
        this.expectedTopic = expectedTopic;
    }

    AuditRecord validate(String topic, String orderingKey, String consumerName, JsonNode envelope) {
        requireFields(envelope, ENVELOPE_FIELDS, REQUIRED_ENVELOPE_FIELDS, "envelope");
        equal("1.0", text(envelope, "specversion"), "specversion");
        equal(TenantCreatedEventValidator.SOURCE, text(envelope, "source"), "source");
        equal(EVENT_TYPE, text(envelope, "type"), "type");
        equal("application/json", text(envelope, "datacontenttype"), "datacontenttype");
        equal(DATASCHEMA, text(envelope, "dataschema"), "dataschema");
        equal(expectedTopic, topic, "topic");
        equal(TenantCreatedEventValidator.CONSUMER_NAME, consumerName, "consumer");
        UUID eventId = uuid(text(envelope, "id"), "id");
        Instant occurredAt = instant(text(envelope, "time"), "time");
        JsonNode trace = envelope.get("traceId");
        String traceId = trace == null ? null : text(envelope, "traceId");
        if (traceId != null && !TRACE_ID.matcher(traceId).matches()) throw invalid("traceId");

        JsonNode data = envelope.path("data");
        requireFields(data, DATA_FIELDS, DATA_FIELDS, "data");
        UUID tenantId = uuid(text(data, "tenantId"), "data.tenantId");
        UUID actorId = uuid(text(data, "actorIdentityId"), "data.actorIdentityId");
        String displayName = text(data, "displayName");
        if (displayName.isBlank() || displayName.length() > 200) throw invalid("data.displayName");
        JsonNode expiry = data.get("expiresAt");
        if (expiry == null || (!expiry.isNull() && !expiry.isTextual())) throw invalid("data.expiresAt");
        String expiresAt = expiry.isNull() ? null : instant(expiry.asText(), "data.expiresAt").toString();
        equal(tenantId.toString(), text(envelope, "subject"), "subject");
        equal(tenantId.toString(), orderingKey, "orderingKey");

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("displayName", displayName);
        metadata.put("expiresAt", expiresAt);
        return new AuditRecord(eventId, TenantCreatedEventValidator.SOURCE, EVENT_TYPE,
                occurredAt, traceId, actorId, tenantId,
                "TENANT_DETAILS_UPDATED", "TENANT", tenantId, objectMapper.writeValueAsString(metadata));
    }

    private static void requireFields(JsonNode value, Set<String> allowed, Set<String> required, String field) {
        if (!value.isObject()) throw invalid(field);
        Set<String> actual = new HashSet<>(value.propertyNames());
        if (!allowed.containsAll(actual) || !actual.containsAll(required)) throw invalid(field + " fields");
    }

    private static String text(JsonNode value, String field) {
        JsonNode member = value.get(field);
        if (member == null || !member.isTextual() || member.asText().isBlank()) throw invalid(field);
        return member.asText();
    }

    private static UUID uuid(String value, String field) {
        if (!UUID_V7.matcher(value).matches()) throw invalid(field);
        return UUID.fromString(value);
    }

    private static Instant instant(String value, String field) {
        if (!value.endsWith("Z")) throw invalid(field);
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw invalid(field);
        }
    }

    private static void equal(String expected, String actual, String field) {
        if (!expected.equals(actual)) throw invalid(field);
    }

    private static InvalidAuditEventException invalid(String field) {
        return new InvalidAuditEventException("Tenant Details Updated 契约字段不合法: " + field);
    }
}
