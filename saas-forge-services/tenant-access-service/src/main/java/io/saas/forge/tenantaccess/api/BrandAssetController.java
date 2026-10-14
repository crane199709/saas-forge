package io.saas.forge.tenantaccess.api;

import io.saas.forge.sdk.tenant.TenantContextAccessor;
import io.saas.forge.tenantaccess.application.brand.BrandAsset;
import io.saas.forge.tenantaccess.application.brand.BrandAssetService;
import io.saas.forge.tenantaccess.application.brand.BrandPng;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "saas.forge.brand.s3.bucket")
@RequestMapping
public class BrandAssetController {
    private final TenantContextAccessor contexts;
    private final BrandAssetService service;

    public BrandAssetController(TenantContextAccessor contexts, BrandAssetService service) {
        this.contexts = contexts;
        this.service = service;
    }

    @PostMapping(path = "/api/v1/brand-assets/{kind}", consumes = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<BrandAsset> upload(@PathVariable String kind, HttpServletRequest request) throws IOException {
        BrandPng.Kind parsed = switch (kind) {
            case "logo" -> BrandPng.Kind.LOGO;
            case "favicon" -> BrandPng.Kind.FAVICON;
            default -> throw new BrandPng.Invalid();
        };
        int maximum = parsed == BrandPng.Kind.LOGO ? 2 * 1024 * 1024 : 256 * 1024;
        byte[] bytes = request.getInputStream().readNBytes(maximum + 1);
        if (bytes.length > maximum) throw new BrandPng.TooLarge();
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(service.upload(contexts.requireCurrent(), parsed, bytes));
    }

    @GetMapping(path = "/brands/{assetId}")
    public ResponseEntity<byte[]> read(@PathVariable UUID assetId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=brand.png")
                .contentType(MediaType.IMAGE_PNG).body(service.publicRead(assetId));
    }
}
