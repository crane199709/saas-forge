package io.saas.forge.tenantaccess.api;

import io.saas.forge.sdk.tenant.TenantContextAccessor;
import io.saas.forge.tenantaccess.application.brand.BrandAssetService;
import io.saas.forge.tenantaccess.application.brand.BrandPng;
import io.saas.forge.tenantaccess.contract.api.BrandAssetsApi;
import io.saas.forge.tenantaccess.contract.model.BrandAsset;
import java.io.IOException;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "saas.forge.brand.s3.bucket")
public class BrandAssetController implements BrandAssetsApi {
    private final TenantContextAccessor contexts;
    private final BrandAssetService service;

    public BrandAssetController(TenantContextAccessor contexts, BrandAssetService service) {
        this.contexts = contexts;
        this.service = service;
    }

    @Override
    public ResponseEntity<BrandAsset> uploadBrandAsset(String kind, Resource body) {
        BrandPng.Kind parsed = switch (kind) {
            case "logo" -> BrandPng.Kind.LOGO;
            case "favicon" -> BrandPng.Kind.FAVICON;
            default -> throw new BrandPng.Invalid();
        };
        int maximum = parsed == BrandPng.Kind.LOGO ? 2 * 1024 * 1024 : 256 * 1024;
        byte[] bytes;
        try (var input = body.getInputStream()) {
            bytes = input.readNBytes(maximum + 1);
        } catch (IOException failure) {
            throw new BrandPng.Invalid();
        }
        if (bytes.length > maximum) throw new BrandPng.TooLarge();
        var uploaded = service.upload(contexts.requireCurrent(), parsed, bytes);
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(new BrandAsset(uploaded.id(), uploaded.tenantId(),
                        BrandAsset.KindEnum.valueOf(uploaded.kind().name()),
                        uploaded.url(), uploaded.width(), uploaded.height()));
    }

    @Override
    public ResponseEntity<Resource> readPublicBrandAsset(UUID assetId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=brand.png")
                .contentType(MediaType.IMAGE_PNG).body(new ByteArrayResource(service.publicRead(assetId)));
    }
}
