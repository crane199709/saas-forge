package io.saasforge.iam.datasecurity.controller;

import io.saasforge.iam.datasecurity.service.DataSecurityService;
import io.saasforge.sdk.core.CurrentTenant;
import io.saasforge.sdk.core.api.ApiResponse;
import io.saasforge.sdk.security.EffectiveFieldPolicy;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
class InternalFieldMaskingController {
    private final DataSecurityService security;

    InternalFieldMaskingController(DataSecurityService security) {
        this.security = security;
    }

    @GetMapping("/internal/field-masking/{tenantId}")
    ApiResponse<Map<String, EffectiveFieldPolicy>> policies(@PathVariable UUID tenantId, Authentication service) {
        try (var ignored = CurrentTenant.bind(tenantId)) {
            return ApiResponse.ok(security.policies(tenantId, service.getName()));
        }
    }
}
