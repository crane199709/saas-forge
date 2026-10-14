package io.saas.forge.tenantaccess.api;

import io.saas.forge.sdk.tenant.TenantContextAccessor;
import io.saas.forge.tenantaccess.application.brand.BrandProfileChange;
import io.saas.forge.tenantaccess.application.brand.BrandProfileRevision;
import io.saas.forge.tenantaccess.application.brand.BrandProfileService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/brand-profile")
public class BrandProfileController {
    private final TenantContextAccessor contexts;
    private final BrandProfileService service;

    public BrandProfileController(TenantContextAccessor contexts, BrandProfileService service) {
        this.contexts = contexts;
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<BrandProfileRevision> read() {
        return response(service.read(contexts.requireCurrent()));
    }

    @PutMapping
    public ResponseEntity<BrandProfileRevision> save(@RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody BrandProfileChange change) {
        if (ifMatch == null) throw new BrandProfilePreconditionRequired();
        if (!ifMatch.matches("^\"(0|[1-9][0-9]*)\"$")) throw new BrandProfileService.Invalid();
        long revision;
        try { revision = Long.parseLong(ifMatch.substring(1, ifMatch.length() - 1)); }
        catch (NumberFormatException invalid) { throw new BrandProfileService.Invalid(); }
        return response(service.save(contexts.requireCurrent(), revision, change));
    }

    private static ResponseEntity<BrandProfileRevision> response(BrandProfileRevision value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .eTag("\"" + value.revision() + "\"").body(value);
    }

    static class BrandProfilePreconditionRequired extends RuntimeException {}
}
