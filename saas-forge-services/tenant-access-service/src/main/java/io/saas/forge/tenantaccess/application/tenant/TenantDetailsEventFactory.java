package io.saas.forge.tenantaccess.application.tenant;

import io.saas.forge.tenantaccess.domain.outbox.OutboxEvent;
import io.saas.forge.tenantaccess.domain.tenant.Tenant;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

public final class TenantDetailsEventFactory {
    public static final String EVENT_TYPE = "com.saas.forge.tenant.details-updated.v1";
    private final ObjectMapper objectMapper;
    private final UuidV7Generator ids;
    private final String topic;

    public TenantDetailsEventFactory(ObjectMapper objectMapper, UuidV7Generator ids, String topic) {
        this.objectMapper = objectMapper;
        this.ids = ids;
        this.topic = topic;
    }

    public OutboxEvent create(Tenant tenant, UUID actorIdentityId, Instant occurredAt, String traceId) {
        UUID eventId = ids.next();
        Instant eventTime = occurredAt.truncatedTo(ChronoUnit.MILLIS);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tenantId", tenant.id().toString());
        data.put("actorIdentityId", actorIdentityId.toString());
        data.put("displayName", tenant.displayName());
        data.put("expiresAt", tenant.expiresAt() == null ? null : tenant.expiresAt().toString());

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("specversion", "1.0");
        snapshot.put("id", eventId.toString());
        snapshot.put("source", "urn:saas.forge:tenant-access-service");
        snapshot.put("type", EVENT_TYPE);
        snapshot.put("subject", tenant.id().toString());
        snapshot.put("time", eventTime.toString());
        snapshot.put("datacontenttype", "application/json");
        snapshot.put("dataschema", "https://saas.forge.io/contracts/events/tenant-details-updated.v1.schema.json");
        if (traceId != null) snapshot.put("traceId", traceId);
        snapshot.put("data", data);
        return new OutboxEvent(eventId, tenant.id(), eventTime, topic, tenant.id().toString(), traceId,
                objectMapper.writeValueAsString(snapshot));
    }
}
