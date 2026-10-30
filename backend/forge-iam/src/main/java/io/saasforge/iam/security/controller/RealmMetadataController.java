package io.saasforge.iam.security.controller;

import io.swagger.v3.oas.annotations.tags.Tag;

import io.saasforge.iam.security.service.RealmMetadataService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** OIDC 发现与 JWKS 按标准格式输出，不使用响应信封（ADR-0015 的适用范围不含 OIDC 端点）。 */
@Tag(name = "认证元数据")
@RestController
class RealmMetadataController {
    private final RealmMetadataService metadata;

    RealmMetadataController(RealmMetadataService metadata) {
        this.metadata = metadata;
    }

    @GetMapping("/.well-known/openid-configuration")
    Map<String, Object> tenantConfiguration() {
        return metadata.tenantConfiguration();
    }

    @GetMapping("/platform/.well-known/openid-configuration")
    Map<String, Object> platformConfiguration() {
        return metadata.platformConfiguration();
    }

    // Tenant Realm 的公钥由 Authorization Server 的标准 /oauth2/jwks 发布。
    @GetMapping("/platform/oauth2/jwks")
    Map<String, Object> platformJwks() {
        return metadata.platformJwks();
    }
}
