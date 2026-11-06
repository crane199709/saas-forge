package io.saasforge.iam.mfa.controller;

import io.saasforge.iam.mfa.model.IdentityMfaStatus;
import io.saasforge.iam.mfa.service.MfaResetService;
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

// 只接受 Platform Realm Token（网关按 /iam/platform/** 校验，iam 以 Platform Realm 的解码器验签），操作人取自 Token 的 sub。
// 查找用 POST：登录标识是个人信息，不放进 URL，免得落入访问日志。
@Tag(name = "MFA 重置")
@RestController
class MfaResetController {
    private final MfaResetService resets;

    MfaResetController(MfaResetService resets) {
        this.resets = resets;
    }

    @Operation(summary = "按账号、邮箱或手机号精确查找身份，只返回标识、展示名与是否已绑定 Authenticator")
    @PostMapping(value = "/platform/identities/lookup", consumes = "application/json")
    ApiResponse<IdentityMfaStatus> lookup(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody LookupRequest body) {
        return ApiResponse.ok(resets.lookupIdentity(actor(jwt), body.identifier()));
    }

    @Operation(summary = "重置身份的 MFA：清除 Authenticator 与全部 Recovery Code，下次登录不再要求 MFA")
    @PostMapping("/platform/identities/{id}/mfa/reset")
    ApiResponse<Void> resetIdentity(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        resets.resetIdentity(actor(jwt), id);
        return ApiResponse.ok();
    }

    @Operation(summary = "重置运营账号的 MFA，只有超级管理员可以操作")
    @PostMapping("/platform/operators/{id}/mfa/reset")
    ApiResponse<Void> resetOperator(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        resets.resetOperator(actor(jwt), id);
        return ApiResponse.ok();
    }

    private static UUID actor(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    record LookupRequest(@NotBlank @Size(max = 254) String identifier) {
        @Override
        public String toString() {
            return "LookupRequest[redacted]";
        }
    }
}
