package io.saas.forge.audit.infrastructure.messaging;

import io.saas.forge.audit.application.AuditRecord;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 仅把已提交的平台角色事实写为 Audit Record；不接收邮箱或 Setup Token。 */
@Component
public final class PlatformAdminGrantedEventValidator {
    public static final String EVENT_TYPE = "com.saas.forge.iam.platform-admin-granted.v1";
    private static final String SOURCE = "urn:saas.forge:iam-service";
    private static final String SCHEMA =
            "https://saas.forge.io/contracts/events/iam-platform-admin-granted.v1.schema.json";
    private static final Set<String> ENVELOPE = Set.of("specversion", "id", "source", "type",
            "subject", "time", "datacontenttype", "dataschema", "data");
    private static final Set<String> DATA = Set.of("identityId", "actorIdentityId",
            "roleAssignmentId", "roleKey", "occurredAt");
    private final ObjectMapper json;
    private final String expectedTopic;

    public PlatformAdminGrantedEventValidator(ObjectMapper json,
            @Value("${saas.forge.audit.iam-session-topic}") String expectedTopic) {
        this.json = json;
        this.expectedTopic = expectedTopic;
    }

    public AuditRecord validate(String topic, String key, String consumer, String payload) {
        try {
            JsonNode event = json.readTree(payload);
            if (!expectedTopic.equals(topic) || !SessionStartedEventValidator.CONSUMER_NAME.equals(consumer)
                    || !event.isObject() || !fields(event, ENVELOPE, true)
                    || !"1.0".equals(text(event, "specversion"))
                    || !SOURCE.equals(text(event, "source"))
                    || !EVENT_TYPE.equals(text(event, "type"))
                    || !"application/json".equals(text(event, "datacontenttype"))
                    || !SCHEMA.equals(text(event, "dataschema"))) throw invalid();
            JsonNode data = event.path("data");
            if (!data.isObject() || !fields(data, DATA, false)
                    || !"PLATFORM_ADMIN".equals(text(data, "roleKey"))) throw invalid();
            UUID eventId = id(text(event, "id"));
            UUID identityId = id(text(data, "identityId"));
            UUID actorId = id(text(data, "actorIdentityId"));
            UUID assignmentId = id(text(data, "roleAssignmentId"));
            Instant at = Instant.parse(text(event, "time"));
            if (!identityId.toString().equals(key) || !identityId.toString().equals(text(event, "subject"))
                    || !at.equals(Instant.parse(text(data, "occurredAt")))) throw invalid();
            String traceId = event.has("traceId") ? text(event, "traceId") : null;
            if (traceId != null && !traceId.matches("^(?!0{32}$)[0-9a-f]{32}$")) throw invalid();
            return new AuditRecord(eventId, SOURCE, EVENT_TYPE, at, traceId, actorId, null,
                    "PLATFORM_ADMIN_GRANTED", "PLATFORM_ROLE_ASSIGNMENT", assignmentId,
                    json.writeValueAsString(Map.of("identityId", identityId.toString(), "roleKey", "PLATFORM_ADMIN")));
        } catch (InvalidAuditEventException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new InvalidAuditEventException("Platform Admin Granted 契约字段不合法", exception);
        }
    }

    private static boolean fields(JsonNode node, Set<String> required, boolean traceOptional) {
        Set<String> actual = Set.copyOf(node.propertyNames());
        return traceOptional ? actual.equals(required) || actual.equals(withTrace(required)) : actual.equals(required);
    }

    private static Set<String> withTrace(Set<String> fields) {
        var result = new java.util.HashSet<>(fields);
        result.add("traceId");
        return result;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) throw invalid();
        return value.asText();
    }

    private static UUID id(String value) {
        UUID id = UUID.fromString(value);
        if (id.version() != 7 || !id.toString().equals(value)) throw invalid();
        return id;
    }

    private static InvalidAuditEventException invalid() {
        return new InvalidAuditEventException("Platform Admin Granted 契约字段不合法");
    }
}
