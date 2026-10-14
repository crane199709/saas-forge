package io.saas.forge.tenantaccess.application.brand;

import java.util.UUID;

public record BrandAsset(UUID id, UUID tenantId, BrandPng.Kind kind, String url, int width, int height) {}
