package io.saas.forge.audit.infrastructure.messaging;

import io.saas.forge.audit.application.AuditRecord;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class InvitationEventValidator {
    private static final Set<String> ENVELOPE_FIELDS = Set.of(
            "specversion", "id", "source", "type", "subject", "time",
            "datacontenttype", "dataschema", "traceId", "data");
    private static final Set<String> REQUIRED_ENVELOPE_FIELDS = Set.of(
            "specversion", "id", "source", "type", "subject", "time",
            "datacontenttype", "dataschema", "data");
    private static final Set<String> DATA_FIELDS =
            Set.of("invitationId", "tenantId", "actorIdentityId", "expiresAt");
    private static final Pattern UUID_V7 = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");
    private static final Pattern TRACE_ID = Pattern.compile("^(?!0{32}$)[0-9a-f]{32}$");
    private final ObjectMapper json;
    private final String expectedTopic;

    InvitationEventValidator(ObjectMapper json, String expectedTopic) {
        this.json = json;
        this.expectedTopic = expectedTopic;
    }

    AuditRecord validate(String topic, String orderingKey, String consumerName, JsonNode envelope) {
        fields(envelope, ENVELOPE_FIELDS, REQUIRED_ENVELOPE_FIELDS);
        equal("1.0", text(envelope, "specversion"));
        equal("urn:saas.forge:tenant-access-service", text(envelope, "source"));
        String type = text(envelope, "type");
        String action = switch (type) {
            case "com.saas.forge.invitation.created.v1" -> "INVITATION_CREATED";
            case "com.saas.forge.invitation.resent.v1" -> "INVITATION_RESENT";
            case "com.saas.forge.invitation.revoked.v1" -> "INVITATION_REVOKED";
            default -> throw invalid("type");
        };
        String verb = action.substring("INVITATION_".length()).toLowerCase(java.util.Locale.ROOT);
        equal("https://saas.forge.io/contracts/events/invitation-" + verb + ".v1.schema.json",
                text(envelope, "dataschema"));
        equal("application/json", text(envelope, "datacontenttype"));
        equal(expectedTopic, topic);
        equal(TenantCreatedEventValidator.CONSUMER_NAME, consumerName);
        UUID eventId = uuid(text(envelope, "id"));
        Instant occurredAt = instant(text(envelope, "time"));
        String traceId = envelope.get("traceId") == null ? null : text(envelope, "traceId");
        if (traceId != null && !TRACE_ID.matcher(traceId).matches()) throw invalid("traceId");
        JsonNode data = envelope.path("data");
        fields(data, DATA_FIELDS, DATA_FIELDS);
        UUID tenantId = uuid(text(data, "tenantId"));
        UUID invitationId = uuid(text(data, "invitationId"));
        UUID actorId = uuid(text(data, "actorIdentityId"));
        Instant expiresAt = instant(text(data, "expiresAt"));
        equal(tenantId.toString(), text(envelope, "subject"));
        equal(tenantId.toString(), orderingKey);
        return new AuditRecord(eventId, "urn:saas.forge:tenant-access-service", type,
                occurredAt, traceId, actorId, tenantId, action, "INVITATION", invitationId,
                json.writeValueAsString(Map.of("expiresAt", expiresAt.toString())));
    }

    private static void fields(JsonNode node, Set<String> allowed, Set<String> required) {
        if (!node.isObject()) throw invalid("fields");
        Set<String> actual = new HashSet<>(node.propertyNames());
        if (!allowed.containsAll(actual) || !actual.containsAll(required)) throw invalid("fields");
    }

    private static String text(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || !value.isTextual() || value.asText().isBlank()) throw invalid(name);
        return value.asText();
    }

    private static UUID uuid(String value) {
        if (!UUID_V7.matcher(value).matches()) throw invalid("UUID");
        return UUID.fromString(value);
    }

    private static Instant instant(String value) {
        if (!value.endsWith("Z")) throw invalid("time");
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException failure) {
            throw invalid("time");
        }
    }

    private static void equal(String expected, String actual) {
        if (!expected.equals(actual)) throw invalid("value");
    }

    private static InvalidAuditEventException invalid(String field) {
        return new InvalidAuditEventException("Invitation 事件契约字段不合法: " + field);
    }
}
