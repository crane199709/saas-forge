package io.saas.forge.tenantaccess.application.organization;

import io.saas.forge.sdk.tenant.TenantContextSnapshot;
import io.saas.forge.tenantaccess.application.tenant.UuidV7Generator;
import io.saas.forge.tenantaccess.domain.outbox.OutboxEvent;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

public final class MembershipOrganizationEventFactory {
    public static final String TYPE = "com.saas.forge.membership.organization-unit-changed.v1";
    private final ObjectMapper json;
    private final UuidV7Generator ids;
    private final Clock clock;
    private final String topic;

    public MembershipOrganizationEventFactory(ObjectMapper json, UuidV7Generator ids, Clock clock, String topic) {
        this.json = json;
        this.ids = ids;
        this.clock = clock;
        this.topic = topic;
    }

    public OutboxEvent create(TenantContextSnapshot context, UUID membershipId, UUID unitId, String action) {
        UUID id = ids.next();
        var at = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        return new OutboxEvent(id, context.tenantId(), at, topic, context.tenantId().toString(), null,
                json.writeValueAsString(Map.of(
                        "specversion", "1.0", "id", id.toString(),
                        "source", "urn:saas.forge:tenant-access-service", "type", TYPE,
                        "subject", context.tenantId().toString(), "time", at.toString(),
                        "datacontenttype", "application/json",
                        "dataschema", "https://saas.forge.io/contracts/events/membership-organization-unit-changed.v1.schema.json",
                        "data", Map.of("tenantId", context.tenantId(), "membershipId", membershipId,
                                "unitId", unitId, "actorIdentityId", context.identityId(), "action", action))));
    }
}
