package io.saas.forge.tenantaccess.api;

import io.saas.forge.sdk.tenant.TenantContextAccessor;
import io.saas.forge.tenantaccess.application.brand.BrandProfileService;
import io.saas.forge.tenantaccess.contract.api.BrandProfilesApi;
import io.saas.forge.tenantaccess.contract.model.BrandProfileChange;
import io.saas.forge.tenantaccess.contract.model.BrandProfileRevision;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BrandProfileController implements BrandProfilesApi {
    private final TenantContextAccessor contexts;
    private final BrandProfileService service;

    public BrandProfileController(TenantContextAccessor contexts, BrandProfileService service) {
        this.contexts = contexts;
        this.service = service;
    }

    @Override
    public ResponseEntity<BrandProfileRevision> getTenantBrandProfile() {
        return response(service.read(contexts.requireCurrent()));
    }

    @Override
    public ResponseEntity<BrandProfileRevision> saveTenantBrandProfile(String ifMatch, BrandProfileChange change) {
        if (ifMatch == null) throw new BrandProfilePreconditionRequired();
        if (!ifMatch.matches("^\"(0|[1-9][0-9]*)\"$")) throw new BrandProfileService.Invalid();
        long revision;
        try { revision = Long.parseLong(ifMatch.substring(1, ifMatch.length() - 1)); }
        catch (NumberFormatException invalid) { throw new BrandProfileService.Invalid(); }
        return response(service.save(contexts.requireCurrent(), revision,
                new io.saas.forge.tenantaccess.application.brand.BrandProfileChange(
                        change.getDisplayName(), change.getLogoUrl(), change.getFaviconUrl(),
                        change.getPrimaryColor(), change.getAccentColor())));
    }

    private static ResponseEntity<BrandProfileRevision> response(
            io.saas.forge.tenantaccess.application.brand.BrandProfileRevision value) {
        io.saas.forge.tenantaccess.contract.model.TenantBrandProfile profile = null;
        if (value.profile() != null) {
            var saved = value.profile();
            profile = new io.saas.forge.tenantaccess.contract.model.TenantBrandProfile(
                    saved.displayName(), saved.primaryColor(), saved.accentColor())
                    .logoUrl(saved.logoUrl()).faviconUrl(saved.faviconUrl());
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .eTag("\"" + value.revision() + "\"")
                .body(new BrandProfileRevision(value.revision()).profile(profile));
    }

    static class BrandProfilePreconditionRequired extends RuntimeException {}
}
