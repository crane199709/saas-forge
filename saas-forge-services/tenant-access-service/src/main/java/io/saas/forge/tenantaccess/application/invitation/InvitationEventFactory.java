package io.saas.forge.tenantaccess.application.invitation;

import io.saas.forge.tenantaccess.application.tenant.UuidV7Generator;
import io.saas.forge.tenantaccess.domain.invitation.Invitation;
import io.saas.forge.tenantaccess.domain.outbox.OutboxEvent;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

public final class InvitationEventFactory {
    private final ObjectMapper json;
    private final UuidV7Generator ids;
    private final String topic;

    public InvitationEventFactory(ObjectMapper json, UuidV7Generator ids, String topic) {
        this.json = json;
        this.ids = ids;
        this.topic = topic;
    }

    public OutboxEvent create(String action, Invitation invitation, UUID actorIdentityId, Instant now, String traceId) {
        if (!java.util.Set.of("created", "resent", "revoked").contains(action)) {
            throw new IllegalArgumentException("Invitation 事件动作不合法");
        }
        UUID eventId = ids.next();
        Instant occurredAt = now.truncatedTo(ChronoUnit.MILLIS);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("invitationId", invitation.id().toString());
        data.put("tenantId", invitation.tenantId().toString());
        data.put("actorIdentityId", actorIdentityId.toString());
        data.put("expiresAt", invitation.expiresAt().toString());
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("specversion", "1.0");
        envelope.put("id", eventId.toString());
        envelope.put("source", "urn:saas.forge:tenant-access-service");
        envelope.put("type", "com.saas.forge.invitation." + action + ".v1");
        envelope.put("subject", invitation.tenantId().toString());
        envelope.put("time", occurredAt.toString());
        envelope.put("datacontenttype", "application/json");
        envelope.put("dataschema", "https://saas.forge.io/contracts/events/invitation-" + action + ".v1.schema.json");
        if (traceId != null) envelope.put("traceId", traceId);
        envelope.put("data", data);
        return new OutboxEvent(eventId, invitation.tenantId(), occurredAt, topic,
                invitation.tenantId().toString(), traceId, json.writeValueAsString(envelope));
    }

    public OutboxEvent accepted(Invitation invitation, UUID identityId, UUID membershipId,
                                Instant now, String traceId) {
        UUID eventId = ids.next();
        Instant occurredAt = now.truncatedTo(ChronoUnit.MILLIS);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("invitationId", invitation.id().toString());
        data.put("tenantId", invitation.tenantId().toString());
        data.put("actorIdentityId", identityId.toString());
        data.put("identityId", identityId.toString());
        data.put("membershipId", membershipId.toString());
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("specversion", "1.0");
        envelope.put("id", eventId.toString());
        envelope.put("source", "urn:saas.forge:tenant-access-service");
        envelope.put("type", "com.saas.forge.invitation.accepted.v1");
        envelope.put("subject", invitation.tenantId().toString());
        envelope.put("time", occurredAt.toString());
        envelope.put("datacontenttype", "application/json");
        envelope.put("dataschema", "https://saas.forge.io/contracts/events/invitation-accepted.v1.schema.json");
        if (traceId != null) envelope.put("traceId", traceId);
        envelope.put("data", data);
        return new OutboxEvent(eventId, invitation.tenantId(), occurredAt, topic,
                invitation.tenantId().toString(), traceId, json.writeValueAsString(envelope));
    }
}
