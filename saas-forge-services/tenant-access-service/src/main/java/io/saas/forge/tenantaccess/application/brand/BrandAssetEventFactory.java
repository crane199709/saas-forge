package io.saas.forge.tenantaccess.application.brand;

import io.saas.forge.sdk.tenant.TenantContextSnapshot;
import io.saas.forge.tenantaccess.application.tenant.UuidV7Generator;
import io.saas.forge.tenantaccess.domain.outbox.OutboxEvent;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

public class BrandAssetEventFactory {
    public static final String TYPE = "com.saas.forge.brand.asset-uploaded.v1";
    private final ObjectMapper json;
    private final UuidV7Generator ids;
    private final Clock clock;
    private final String topic;

    public BrandAssetEventFactory(ObjectMapper json, UuidV7Generator ids, Clock clock, String topic) {
        this.json = json;
        this.ids = ids;
        this.clock = clock;
        this.topic = topic;
    }

    public OutboxEvent uploaded(TenantContextSnapshot context, BrandAsset asset) {
        var id = ids.next();
        var at = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        return new OutboxEvent(id, context.tenantId(), at, topic, context.tenantId().toString(), null,
                json.writeValueAsString(Map.of(
                        "specversion", "1.0", "id", id.toString(),
                        "source", "urn:saas.forge:tenant-access-service",
                        "type", TYPE, "subject", context.tenantId().toString(), "time", at.toString(),
                        "datacontenttype", "application/json",
                        "dataschema", "https://saas.forge.io/contracts/events/brand-asset-uploaded.v1.schema.json",
                        "data", Map.of("tenantId", context.tenantId(), "assetId", asset.id(),
                                "actorIdentityId", context.identityId(), "kind", asset.kind().name()))));
    }
}
