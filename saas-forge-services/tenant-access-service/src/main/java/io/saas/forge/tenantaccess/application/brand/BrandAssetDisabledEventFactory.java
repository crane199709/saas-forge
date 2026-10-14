package io.saas.forge.tenantaccess.application.brand;

import io.saas.forge.tenantaccess.application.tenant.UuidV7Generator;
import io.saas.forge.tenantaccess.domain.outbox.OutboxEvent;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

public class BrandAssetDisabledEventFactory {
    public static final String TYPE = "com.saas.forge.brand.asset-disabled.v1";
    private final ObjectMapper json;
    private final UuidV7Generator ids;
    private final String topic;

    public BrandAssetDisabledEventFactory(ObjectMapper json, UuidV7Generator ids, String topic) {
        this.json = json;
        this.ids = ids;
        this.topic = topic;
    }

    public OutboxEvent disabled(UUID actor, DisabledBrandAsset asset) {
        var id = ids.next();
        var at = asset.disabledAt();
        return new OutboxEvent(id, asset.tenantId(), at, topic, asset.tenantId().toString(), null,
                json.writeValueAsString(Map.of(
                        "specversion", "1.0", "id", id.toString(),
                        "source", "urn:saas.forge:tenant-access-service",
                        "type", TYPE, "subject", asset.tenantId().toString(), "time", at.toString(),
                        "datacontenttype", "application/json",
                        "dataschema", "https://saas.forge.io/contracts/events/brand-asset-disabled.v1.schema.json",
                        "data", Map.of("tenantId", asset.tenantId(), "assetId", asset.assetId(),
                                "actorIdentityId", actor, "kind", asset.kind().name()))));
    }
}
