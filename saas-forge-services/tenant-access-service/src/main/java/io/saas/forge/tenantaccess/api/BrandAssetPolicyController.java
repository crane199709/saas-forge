package io.saas.forge.tenantaccess.api;

import io.saas.forge.tenantaccess.application.authorization.PlatformAdminAuthorizer;
import io.saas.forge.tenantaccess.application.brand.BrandAssetPolicyService;
import io.saas.forge.tenantaccess.contract.api.PlatformBrandPolicyApi;
import io.saas.forge.tenantaccess.contract.model.BrandAssetDisableResult;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@RestController
@ConditionalOnProperty(name = "saas.forge.brand.s3.bucket")
public class BrandAssetPolicyController implements PlatformBrandPolicyApi {
    private final PlatformAdminAuthorizer admins;
    private final BrandAssetPolicyService policy;

    public BrandAssetPolicyController(PlatformAdminAuthorizer admins, BrandAssetPolicyService policy) {
        this.admins = admins;
        this.policy = policy;
    }

    @Override
    public ResponseEntity<BrandAssetDisableResult> disableBrandAsset(UUID assetId) {
        var request = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
        UUID actor = admins.authorize(request.getHeader(HttpHeaders.AUTHORIZATION));
        var result = policy.disable(actor, assetId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new BrandAssetDisableResult(result.assetId(), result.tenantId(),
                        BrandAssetDisableResult.KindEnum.valueOf(result.kind().name()),
                        OffsetDateTime.ofInstant(result.disabledAt(), ZoneOffset.UTC)));
    }
}
