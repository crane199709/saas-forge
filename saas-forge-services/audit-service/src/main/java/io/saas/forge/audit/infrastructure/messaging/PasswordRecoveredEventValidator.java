package io.saas.forge.audit.infrastructure.messaging;

import io.saas.forge.audit.application.AuditRecord;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 只接受已提交且不含密码、Token、邮箱的 Password Recovery 成功事实。 */
@Component
public final class PasswordRecoveredEventValidator {
    public static final String EVENT_TYPE = "com.saas.forge.iam.password.recovered.v1";
    private static final String SOURCE = "urn:saas.forge:iam-service";
    private static final String DATASCHEMA =
            "https://saas.forge.io/contracts/events/iam-password-recovered.v1.schema.json";
    private static final Set<String> ENVELOPE = Set.of(
            "specversion", "id", "source", "type", "subject", "time", "datacontenttype", "dataschema", "data");
    private static final Set<String> DATA = Set.of("identityId", "challengeId", "credentialId", "occurredAt");
    private final ObjectMapper json;
    private final String expectedTopic;

    public PasswordRecoveredEventValidator(ObjectMapper json,
            @Value("${saas.forge.audit.iam-session-topic}") String expectedTopic) {
        this.json = json;
        this.expectedTopic = expectedTopic;
    }

    public AuditRecord validate(String topic, String key, String consumer, String payload) {
        try {
            JsonNode event = json.readTree(payload);
            if (!expectedTopic.equals(topic) || !SessionStartedEventValidator.CONSUMER_NAME.equals(consumer)
                    || !event.isObject() || !Set.copyOf(event.propertyNames()).equals(ENVELOPE)
                    || !"1.0".equals(text(event, "specversion"))
                    || !SOURCE.equals(text(event, "source"))
                    || !EVENT_TYPE.equals(text(event, "type"))
                    || !"application/json".equals(text(event, "datacontenttype"))
                    || !DATASCHEMA.equals(text(event, "dataschema"))) throw invalid();
            JsonNode data = event.path("data");
            if (!data.isObject() || !Set.copyOf(data.propertyNames()).equals(DATA)) throw invalid();
            UUID eventId = id(text(event, "id"));
            UUID identityId = id(text(data, "identityId"));
            UUID challengeId = id(text(data, "challengeId"));
            UUID credentialId = id(text(data, "credentialId"));
            Instant time = utc(text(event, "time"));
            if (!identityId.toString().equals(key) || !identityId.toString().equals(text(event, "subject"))
                    || !time.equals(utc(text(data, "occurredAt")))) throw invalid();
            return new AuditRecord(eventId, SOURCE, EVENT_TYPE, time, null, identityId, null,
                    "PASSWORD_RECOVERED", "IDENTITY", identityId,
                    json.writeValueAsString(java.util.Map.of(
                            "challengeId", challengeId.toString(), "credentialId", credentialId.toString())));
        } catch (InvalidAuditEventException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new InvalidAuditEventException("Password Recovered 契约字段不合法", exception);
        }
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

    private static Instant utc(String value) {
        if (!value.endsWith("Z")) throw invalid();
        return Instant.parse(value);
    }

    private static InvalidAuditEventException invalid() {
        return new InvalidAuditEventException("Password Recovered 契约字段不合法");
    }
}
