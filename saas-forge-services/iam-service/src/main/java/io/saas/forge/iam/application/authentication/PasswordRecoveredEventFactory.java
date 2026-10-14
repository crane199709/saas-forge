package io.saas.forge.iam.application.authentication;

import io.saas.forge.iam.domain.outbox.OutboxEvent;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

public final class PasswordRecoveredEventFactory {
    public static final String EVENT_TYPE = "com.saas.forge.iam.password.recovered.v1";
    private final ObjectMapper json;
    private final UuidV7Generator ids;
    private final String topic;

    public PasswordRecoveredEventFactory(ObjectMapper json, UuidV7Generator ids, String environment) {
        this.json = json;
        this.ids = ids;
        this.topic = "saas.forge." + environment + ".iam-service.events";
    }

    public OutboxEvent create(UUID identityId, UUID challengeId, UUID credentialId, Instant at) {
        UUID eventId = ids.next();
        Instant time = at.truncatedTo(ChronoUnit.MILLIS);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("identityId", identityId.toString());
        data.put("challengeId", challengeId.toString());
        data.put("credentialId", credentialId.toString());
        data.put("occurredAt", time.toString());
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("specversion", "1.0");
        envelope.put("id", eventId.toString());
        envelope.put("source", "urn:saas.forge:iam-service");
        envelope.put("type", EVENT_TYPE);
        envelope.put("subject", identityId.toString());
        envelope.put("time", time.toString());
        envelope.put("datacontenttype", "application/json");
        envelope.put("dataschema", "https://saas.forge.io/contracts/events/iam-password-recovered.v1.schema.json");
        envelope.put("data", data);
        return new OutboxEvent(eventId, time, topic, identityId.toString(), null,
                json.writeValueAsString(envelope));
    }
}
