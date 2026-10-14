package io.saas.forge.tenantaccess.application.brand;

import java.time.Instant;
import java.util.UUID;

/** changed 仅用于确定首次提交是否产生事实；重复禁用不重复产生 Audit Record。 */
public record DisabledBrandAsset(UUID assetId, UUID tenantId, BrandPng.Kind kind,
        Instant disabledAt, boolean changed) {}
