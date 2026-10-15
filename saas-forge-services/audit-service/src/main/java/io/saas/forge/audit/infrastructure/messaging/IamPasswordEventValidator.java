package io.saas.forge.audit.infrastructure.messaging;

import io.saas.forge.audit.application.AuditRecord;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 只将 IAM 首次密码设置的已提交成功事实写入 Audit。 */
@Component
public final class IamPasswordEventValidator {
    public static final String CONSUMER_NAME = "audit-service.iam-password-events";
    public static final String SETUP_DELIVERED = "com.saas.forge.iam.password-setup-delivered.v1";
    public static final String PASSWORD_ESTABLISHED = "com.saas.forge.iam.password.established.v1";
    private static final String SOURCE = "urn:saas.forge:iam-service";
    private static final Set<String> ENVELOPE = Set.of(
            "specversion", "id", "source", "type", "subject", "time", "datacontenttype", "dataschema", "data");
    private static final Set<String> DELIVERED_DATA = Set.of("identityId", "deliveryRequestId", "challengeExpiresAt");
    private static final Set<String> ESTABLISHED_DATA = Set.of("identityId", "credentialId", "result", "occurredAt");
    private static final Pattern UUID_V7 = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");
    private static final Pattern TRACE_ID = Pattern.compile("^(?!0{32}$)[0-9a-f]{32}$");
    private static final ObjectMapper TYPE_READER = new ObjectMapper();
    private final ObjectMapper json;
    private final String topic;

    public IamPasswordEventValidator(ObjectMapper json,
            @Value("${saas.forge.audit.iam-session-topic}") String topic) {
        this.json = json;
        this.topic = topic;
    }

    public static boolean isPasswordEvent(String payload) {
        try {
            JsonNode event = TYPE_READER.readTree(payload);
            String type = event.path("type").asText();
            return SETUP_DELIVERED.equals(type) || PASSWORD_ESTABLISHED.equals(type);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    public AuditRecord validate(String messageTopic, String orderingKey, String consumerName, String payload) {
        try {
            JsonNode event = json.readTree(payload);
            String type = text(event, "type");
            if (!topic.equals(messageTopic) || !CONSUMER_NAME.equals(consumerName)
                    || !SOURCE.equals(text(event, "source")) || !"1.0".equals(text(event, "specversion"))
                    || !"application/json".equals(text(event, "datacontenttype"))
                    || !fields(event, ENVELOPE, Set.of("traceId"))) throw invalid();
            JsonNode trace = event.get("traceId");
            String traceId = trace == null ? null : text(event, "traceId");
            if (traceId != null && !TRACE_ID.matcher(traceId).matches()) throw invalid();
            UUID eventId = id(text(event, "id"));
            Instant time = utc(text(event, "time"));
            JsonNode data = event.path("data");
            UUID identityId = id(text(data, "identityId"));
            if (!identityId.toString().equals(orderingKey)
                    || !identityId.toString().equals(text(event, "subject"))) throw invalid();
            String action;
            Map<String, String> metadata;
            if (SETUP_DELIVERED.equals(type)) {
                if (!fields(data, DELIVERED_DATA, Set.of()) || !schema(event, "iam-password-setup-delivered"))
                    throw invalid();
                UUID deliveryId = id(text(data, "deliveryRequestId"));
                Instant expiresAt = utc(text(data, "challengeExpiresAt"));
                action = "PASSWORD_SETUP_DELIVERED";
                metadata = Map.of("deliveryRequestId", deliveryId.toString(),
                        "challengeExpiresAt", expiresAt.toString());
            } else if (PASSWORD_ESTABLISHED.equals(type)) {
                if (!fields(data, ESTABLISHED_DATA, Set.of()) || !schema(event, "iam-password-established")
                        || !"PASSWORD_ESTABLISHED".equals(text(data, "result"))
                        || !time.equals(utc(text(data, "occurredAt")))) throw invalid();
                UUID credentialId = id(text(data, "credentialId"));
                action = "PASSWORD_ESTABLISHED";
                metadata = Map.of("credentialId", credentialId.toString());
            } else throw invalid();
            return new AuditRecord(eventId, SOURCE, type, time, traceId, identityId, null,
                    action, "IDENTITY", identityId, json.writeValueAsString(metadata));
        } catch (InvalidAuditEventException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new InvalidAuditEventException("IAM Password 契约字段不合法", exception);
        }
    }

    private static boolean schema(JsonNode event, String name) {
        return ("https://saas.forge.io/contracts/events/" + name + ".v1.schema.json")
                .equals(text(event, "dataschema"));
    }

    private static boolean fields(JsonNode node, Set<String> required, Set<String> optional) {
        if (!node.isObject()) return false;
        Set<String> actual = new HashSet<>(node.propertyNames());
        Set<String> allowed = new HashSet<>(required);
        allowed.addAll(optional);
        return actual.containsAll(required) && allowed.containsAll(actual);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) throw invalid();
        return value.asText();
    }

    private static UUID id(String value) {
        if (!UUID_V7.matcher(value).matches()) throw invalid();
        return UUID.fromString(value);
    }

    private static Instant utc(String value) {
        if (!value.endsWith("Z")) throw invalid();
        return Instant.parse(value);
    }

    private static InvalidAuditEventException invalid() {
        return new InvalidAuditEventException("IAM Password 契约字段不合法");
    }
}
