package io.saas.forge.tenantaccess.application.brand;

import io.saas.forge.sdk.tenant.TenantContextSnapshot;
import io.saas.forge.tenantaccess.application.authorization.PermissionAuthority;
import io.saas.forge.tenantaccess.domain.outbox.OutboxEventRepository;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

public class BrandProfileService {
    private final PermissionAuthority authority;
    private final BrandProfileRepository profiles;
    private final OutboxEventRepository outbox;
    private final BrandProfileEventFactory events;

    public BrandProfileService(PermissionAuthority authority, BrandProfileRepository profiles,
            OutboxEventRepository outbox, BrandProfileEventFactory events) {
        this.authority = authority;
        this.profiles = profiles;
        this.outbox = outbox;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public BrandProfileRevision read(TenantContextSnapshot context) {
        authorize(context);
        return profiles.read(context.tenantId());
    }

    @Transactional
    public BrandProfileRevision save(TenantContextSnapshot context, long expectedRevision, BrandProfileChange change) {
        authorize(context);
        if (expectedRevision < 0) throw new Invalid();
        validate(change);
        BrandProfileRevision saved = profiles.save(context.tenantId(), expectedRevision, change);
        outbox.append(events.saved(context, saved));
        return saved;
    }

    private void authorize(TenantContextSnapshot context) {
        if (!authority.allowed(context, "system:brand:manage")) throw new Forbidden();
    }

    private static void validate(BrandProfileChange value) {
        if (value == null || value.displayName() == null || value.displayName().isBlank()
                || value.displayName().length() > 200 || value.displayName().codePoints().anyMatch(c -> c < 32 || c == 127)
                || assetId(value.logoUrl()) == null || assetId(value.faviconUrl()) == null
                || !readableColor(value.primaryColor()) || !readableColor(value.accentColor())) throw new Invalid();
    }

    public static UUID assetId(String reference) {
        if (reference == null || !reference.matches("^/brands/[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"))
            return null;
        return UUID.fromString(reference.substring(8));
    }

    private static boolean readableColor(String value) {
        if (value == null || !value.matches("^#[0-9A-Fa-f]{6}$")) return false;
        double luminance = 0;
        double[] weights = {0.2126, 0.7152, 0.0722};
        for (int i = 0; i < 3; i++) {
            double channel = Integer.parseInt(value.substring(1 + i * 2, 3 + i * 2), 16) / 255.0;
            luminance += weights[i] * (channel <= 0.04045 ? channel / 12.92 : Math.pow((channel + 0.055) / 1.055, 2.4));
        }
        return 1.05 / (luminance + 0.05) >= 4.5;
    }

    public static class Forbidden extends RuntimeException {}
    public static class Invalid extends RuntimeException {}
    public static class Stale extends RuntimeException {}
    public static class AssetUnavailable extends RuntimeException {}
}
