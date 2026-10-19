package io.saas.forge.tenantaccess.application.tenant;

import io.saas.forge.tenantaccess.domain.outbox.OutboxEvent;
import io.saas.forge.tenantaccess.domain.tenant.Tenant;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

public final class TenantSuspendedEventFactory {
    public static final String EVENT_TYPE = "com.saas.forge.tenant.suspended.v1";
    private static final String SOURCE = "urn:saas.forge:tenant-access-service";

    private final ObjectMapper objectMapper;
    private final UuidV7Generator ids;
    private final String topic;

    public TenantSuspendedEventFactory(ObjectMapper objectMapper, UuidV7Generator ids, String topic) {
        this.objectMapper = objectMapper;
        this.ids = ids;
        this.topic = topic;
    }

    public OutboxEvent create(
            Tenant tenant, UUID actorIdentityId, long revokedSessionCount, Instant occurredAt, String traceId) {
        return create(tenant, actorIdentityId, revokedSessionCount, occurredAt, traceId,
                EVENT_TYPE, "tenant-suspended.v1.schema.json");
    }

    public OutboxEvent createClosure(
            Tenant tenant, UUID actorIdentityId, long revokedSessionCount, Instant occurredAt, String traceId) {
        return create(tenant, actorIdentityId, revokedSessionCount, occurredAt, traceId,
                "com.saas.forge.tenant.closed.v1", "tenant-closed.v1.schema.json");
    }

    private OutboxEvent create(
            Tenant tenant, UUID actorIdentityId, long revokedSessionCount, Instant occurredAt, String traceId,
            String eventType, String schema) {
        UUID eventId = ids.next();
        Instant eventTime = occurredAt.truncatedTo(ChronoUnit.MILLIS);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tenantId", tenant.id().toString());
        data.put("actorIdentityId", actorIdentityId.toString());
        // 对外只保留既有 Session 计数，不暴露 IAM 的 jti 级明细。
        data.put("revokedSessionCount", revokedSessionCount);

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("specversion", "1.0");
        snapshot.put("id", eventId.toString());
        snapshot.put("source", SOURCE);
        snapshot.put("type", eventType);
        snapshot.put("subject", tenant.id().toString());
        snapshot.put("time", eventTime.toString());
        snapshot.put("datacontenttype", "application/json");
        snapshot.put("dataschema", "https://saas.forge.io/contracts/events/" + schema);
        if (traceId != null) snapshot.put("traceId", traceId);
        snapshot.put("data", data);
        return new OutboxEvent(eventId, tenant.id(), eventTime, topic, tenant.id().toString(), traceId,
                objectMapper.writeValueAsString(snapshot));
    }
}
