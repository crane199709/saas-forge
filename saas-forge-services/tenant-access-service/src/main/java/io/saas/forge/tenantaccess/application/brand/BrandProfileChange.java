package io.saas.forge.tenantaccess.application.brand;

public record BrandProfileChange(String displayName, String logoUrl, String faviconUrl,
        String primaryColor, String accentColor) {}
