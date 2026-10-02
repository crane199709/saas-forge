package io.saas.forge.audit.infrastructure.messaging;

import io.saas.forge.audit.application.AuditRecord;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 只接受六个已登记成功事实；额外字段、正文、名称和描述直接拒绝。 */
@Component
public class ExampleFactEventValidator {
    public static final String CONSUMER_NAME = "audit-service.example-events";
    public static final String SOURCE = "urn:saas.forge:project-service";
    public static final Set<String> TYPES = Set.of(
            "com.saas.forge.example.project.created.v1", "com.saas.forge.example.project.updated.v1",
            "com.saas.forge.example.project.deleted.v1", "com.saas.forge.example.task.created.v1",
            "com.saas.forge.example.task.updated.v1", "com.saas.forge.example.task.deleted.v1");
    private static final Set<String> REQUIRED = Set.of("specversion", "id", "source", "type", "subject", "time",
            "datacontenttype", "dataschema", "data");
    private static final Set<String> DATA = Set.of("tenantId", "actorIdentityId", "membershipId", "resourceId", "projectId", "version");
    private final ObjectMapper json;
    private final String topic;

    public ExampleFactEventValidator(ObjectMapper json,
            @Value("${saas.forge.audit.example-topic}") String topic) {
        if (!topic.matches("saas\\.forge\\.[a-z][a-z0-9-]*\\.project-service\\.events"))
            throw new IllegalArgumentException("Example Topic 非法");
        this.json = json; this.topic = topic;
    }

    public AuditRecord validate(String topic, String key, String consumer, String payload) {
        try {
            JsonNode event = json.readTree(payload);
            var allowed = new HashSet<>(REQUIRED); allowed.add("traceId");
            fields(event, REQUIRED, allowed);
            check(this.topic.equals(topic) && CONSUMER_NAME.equals(consumer));
            check("1.0".equals(text(event, "specversion")) && SOURCE.equals(text(event, "source"))
                    && "application/json".equals(text(event, "datacontenttype")));
            String type = text(event, "type"); check(TYPES.contains(type));
            String[] parts = type.split("\\.");
            String resource = parts[4], action = parts[5];
            check(("https://saas.forge.io/contracts/events/example-" + resource + "-" + action
                    + ".v1.schema.json").equals(text(event, "dataschema")));
            var id = uuid(text(event, "id"));
            String time = text(event, "time"); check(time.endsWith("Z"));
            var occurred = Instant.parse(time);
            String trace = event.has("traceId") ? text(event, "traceId") : null;
            check(trace == null || trace.matches("(?!0{32}$)[0-9a-f]{32}"));
            var data = event.path("data"); fields(data, DATA, DATA);
            var tenant = uuid(text(data, "tenantId"));
            var actor = uuid(text(data, "actorIdentityId"));
            var membership = uuid(text(data, "membershipId"));
            var resourceId = uuid(text(data, "resourceId"));
            var project = uuid(text(data, "projectId"));
            check(resourceId.toString().equals(text(event, "subject")) && project.toString().equals(key));
            check(!resource.equals("project") || project.equals(resourceId));
            var version = data.path("version");
            check(version.isIntegralNumber() && version.canConvertToLong() && version.asLong() > 0);
            return new AuditRecord(id, SOURCE, type, occurred, trace, actor, tenant,
                    (resource + "_" + action).toUpperCase(java.util.Locale.ROOT), resource.toUpperCase(java.util.Locale.ROOT),
                    resourceId, json.writeValueAsString(Map.of("membershipId", membership.toString(),
                            "projectId", project.toString(), "version", version.asLong(), "result", "SUCCESS")));
        } catch (InvalidAuditEventException failure) { throw failure; }
        catch (RuntimeException failure) { throw new InvalidAuditEventException("Example 事实非法", failure); }
    }

    private static void fields(JsonNode node, Set<String> required, Set<String> allowed) {
        check(node != null && node.isObject());
        var actual = new HashSet<>(node.propertyNames());
        check(actual.containsAll(required) && allowed.containsAll(actual));
    }
    private static String text(JsonNode node, String field) {
        var value = node.get(field); check(value != null && value.isTextual() && !value.asText().isBlank());
        return value.asText();
    }
    private static UUID uuid(String value) {
        check(value.matches("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"));
        return UUID.fromString(value);
    }
    private static void check(boolean valid) {
        if (!valid) throw new InvalidAuditEventException("Example 事实未通过白名单校验");
    }
}
