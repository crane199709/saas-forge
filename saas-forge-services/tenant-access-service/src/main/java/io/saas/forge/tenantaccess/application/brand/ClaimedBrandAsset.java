package io.saas.forge.tenantaccess.application.brand;

import java.util.UUID;

public record ClaimedBrandAsset(UUID id, UUID tenantId, String objectKey, BrandPng.Kind kind, Reason reason) {
    public enum Reason { UNBOUND, REPLACED, DISABLED, LEGACY }
}
