package io.saasforge.iam.tenant.controller;

import io.saasforge.iam.membership.model.TenantActor;
import io.saasforge.iam.tenant.service.TenantSecurityService;
import io.saasforge.sdk.core.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "安全设置")
@RestController
@RequestMapping("/security-settings")
class TenantSecurityController {
    private final TenantSecurityService security;

    TenantSecurityController(TenantSecurityService security) {
        this.security = security;
    }

    @Operation(summary = "当前租户的安全设置")
    @GetMapping
    ApiResponse<SecuritySettings> settings(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(new SecuritySettings(security.mfaRequired(TenantActor.of(jwt))));
    }

    @Operation(summary = "修改当前租户的安全设置；开启 MFA Requirement 后未完成 MFA 的成员在下一次刷新 Token 时被拦")
    @PutMapping
    ApiResponse<SecuritySettings> update(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SecuritySettings body) {
        return ApiResponse.ok(
                new SecuritySettings(security.updateMfaRequired(TenantActor.of(jwt), body.mfaRequired())));
    }

    /** @param mfaRequired 是否要求进入本租户的授权已完成 MFA */
    record SecuritySettings(@NotNull Boolean mfaRequired) {}
}
