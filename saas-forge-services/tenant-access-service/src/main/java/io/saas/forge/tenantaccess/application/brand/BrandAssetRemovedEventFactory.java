package io.saas.forge.tenantaccess.application.brand;

import io.saas.forge.tenantaccess.application.tenant.UuidV7Generator;
import io.saas.forge.tenantaccess.domain.outbox.OutboxEvent;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

public class BrandAssetRemovedEventFactory {
    public static final String TYPE = "com.saas.forge.brand.asset-removed.v1";
    private final ObjectMapper json;
    private final UuidV7Generator ids;
    private final Clock clock;
    private final String topic;

    public BrandAssetRemovedEventFactory(ObjectMapper json, UuidV7Generator ids, Clock clock, String topic) {
        this.json = json;
        this.ids = ids;
        this.clock = clock;
        this.topic = topic;
    }

    public OutboxEvent removed(ClaimedBrandAsset asset) {
        var id = ids.next();
        var at = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        return new OutboxEvent(id, asset.tenantId(), at, topic, asset.tenantId().toString(), null,
                json.writeValueAsString(Map.of(
                        "specversion", "1.0", "id", id.toString(),
                        "source", "urn:saas.forge:tenant-access-service",
                        "type", TYPE, "subject", asset.tenantId().toString(), "time", at.toString(),
                        "datacontenttype", "application/json",
                        "dataschema", "https://saas.forge.io/contracts/events/brand-asset-removed.v1.schema.json",
                        "data", Map.of("tenantId", asset.tenantId(), "assetId", asset.id(),
                                "kind", asset.kind().name(), "reason", asset.reason().name()))));
    }
}
