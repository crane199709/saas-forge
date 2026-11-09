package io.saasforge.iam.tenant.controller;

import io.saasforge.iam.tenant.service.OperatorTenantIpAllowlistService;
import io.saasforge.sdk.core.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "租户 IP 允许列表")
@RestController
class OperatorTenantIpAllowlistController {
    private final OperatorTenantIpAllowlistService allowlist;

    OperatorTenantIpAllowlistController(OperatorTenantIpAllowlistService allowlist) {
        this.allowlist = allowlist;
    }

    @Operation(summary = "填写原因后紧急清除指定租户的 IP 允许列表，需管理租户权限")
    @PostMapping("/platform/tenants/{tenantId}/ip-allowlist/clear")
    ApiResponse<Void> clear(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID tenantId,
            @Valid @RequestBody ClearIpAllowlistRequest body) {
        allowlist.clear(tenantId, UUID.fromString(jwt.getSubject()), body.reason());
        return ApiResponse.ok();
    }

    record ClearIpAllowlistRequest(
            @NotBlank @Size(max = 500) String reason) {}
}
