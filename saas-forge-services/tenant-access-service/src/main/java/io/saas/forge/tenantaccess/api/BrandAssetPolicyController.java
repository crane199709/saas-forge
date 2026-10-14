package io.saas.forge.tenantaccess.api;

import io.saas.forge.tenantaccess.application.authorization.PlatformAdminAuthorizer;
import io.saas.forge.tenantaccess.application.brand.BrandAssetPolicyService;
import io.saas.forge.tenantaccess.application.brand.BrandPng;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "saas.forge.brand.s3.bucket")
public class BrandAssetPolicyController {
    private final PlatformAdminAuthorizer admins;
    private final BrandAssetPolicyService policy;

    public BrandAssetPolicyController(PlatformAdminAuthorizer admins, BrandAssetPolicyService policy) {
        this.admins = admins;
        this.policy = policy;
    }

    @PostMapping("/api/v1/platform/brand-assets/{assetId}/disable")
    public ResponseEntity<BrandAssetDisableResult> disable(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @PathVariable UUID assetId) {
        UUID actor = admins.authorize(authorization);
        var result = policy.disable(actor, assetId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new BrandAssetDisableResult(result.assetId(), result.tenantId(), result.kind(),
                        result.disabledAt()));
    }

    public record BrandAssetDisableResult(UUID id, UUID tenantId, BrandPng.Kind kind, Instant disabledAt) {}
}
