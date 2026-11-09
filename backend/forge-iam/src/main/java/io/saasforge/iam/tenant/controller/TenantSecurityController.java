package io.saasforge.iam.tenant.controller;

import io.saasforge.iam.membership.model.TenantActor;
import io.saasforge.iam.tenant.model.ValidIpRule;
import io.saasforge.iam.tenant.service.TenantIpAllowlistService;
import io.saasforge.iam.tenant.service.TenantSecurityService;
import io.saasforge.sdk.core.TenantIpAllowlist;
import io.saasforge.sdk.core.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
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
    private final TenantIpAllowlistService allowlist;

    TenantSecurityController(TenantSecurityService security, TenantIpAllowlistService allowlist) {
        this.allowlist = allowlist;
        this.security = security;
    }

    @Operation(summary = "当前租户的安全设置")
    @GetMapping
    ApiResponse<SecuritySettingsResponse> settings(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(new SecuritySettingsResponse(security.mfaRequired(TenantActor.of(jwt))));
    }

    @Operation(summary = "修改当前租户的安全设置；开启 MFA Requirement 后未完成 MFA 的成员在下一次刷新 Token 时被拦")
    @PutMapping
    ApiResponse<SecuritySettingsResponse> update(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UpdateSecuritySettingsRequest body) {
        return ApiResponse.ok(
                new SecuritySettingsResponse(security.updateMfaRequired(TenantActor.of(jwt), body.mfaRequired())));
    }

    @Operation(summary = "读取当前租户 IP 允许列表与当前客户端 IP")
    @GetMapping("/ip-allowlist")
    ApiResponse<IpAllowlistResponse> ipAllowlist(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(
                new IpAllowlistResponse(allowlist.read(TenantActor.of(jwt)), TenantIpAllowlistService.currentIp()));
    }

    @Operation(summary = "修改当前租户 IP 允许列表")
    @PutMapping("/ip-allowlist")
    ApiResponse<IpAllowlistResponse> updateIpAllowlist(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UpdateIpAllowlistRequest body) {
        return ApiResponse.ok(new IpAllowlistResponse(
                allowlist.update(TenantActor.of(jwt), body.rules()), TenantIpAllowlistService.currentIp()));
    }

    record UpdateIpAllowlistRequest(
            @NotNull @Size(max = TenantIpAllowlist.MAX_RULES)
            List<@ValidIpRule String> rules) {}

    record IpAllowlistResponse(List<String> rules, String currentIp) {}

    /** @param mfaRequired 是否要求进入本租户的授权已完成 MFA */
    record UpdateSecuritySettingsRequest(@NotNull Boolean mfaRequired) {}

    /** @param mfaRequired 是否要求进入本租户的授权已完成 MFA */
    record SecuritySettingsResponse(boolean mfaRequired) {}
}
