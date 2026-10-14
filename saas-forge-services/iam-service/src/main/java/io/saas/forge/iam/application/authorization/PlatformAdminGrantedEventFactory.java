package io.saas.forge.iam.application.authorization;

import io.saas.forge.iam.application.authentication.UuidV7Generator;
import io.saas.forge.iam.domain.outbox.OutboxEvent;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

public final class PlatformAdminGrantedEventFactory {
    public static final String EVENT_TYPE = "com.saas.forge.iam.platform-admin-granted.v1";
    private final ObjectMapper json;
    private final UuidV7Generator ids;
    private final String topic;

    public PlatformAdminGrantedEventFactory(ObjectMapper json, UuidV7Generator ids, String environment) {
        this.json = json;
        this.ids = ids;
        this.topic = "saas.forge." + environment + ".iam-service.events";
    }

    public OutboxEvent create(UUID actor, UUID identity, UUID assignment, Instant at, String traceId) {
        UUID eventId = ids.next();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("identityId", identity.toString());
        data.put("actorIdentityId", actor.toString());
        data.put("roleAssignmentId", assignment.toString());
        data.put("roleKey", "PLATFORM_ADMIN");
        data.put("occurredAt", at.toString());
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("specversion", "1.0");
        event.put("id", eventId.toString());
        event.put("source", "urn:saas.forge:iam-service");
        event.put("type", EVENT_TYPE);
        event.put("subject", identity.toString());
        event.put("time", at.toString());
        event.put("datacontenttype", "application/json");
        event.put("dataschema", "https://saas.forge.io/contracts/events/iam-platform-admin-granted.v1.schema.json");
        if (traceId != null) event.put("traceId", traceId);
        event.put("data", data);
        return new OutboxEvent(eventId, at, topic, identity.toString(), traceId, json.writeValueAsString(event));
    }
}
