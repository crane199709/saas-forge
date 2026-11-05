package io.saasforge.iam.mfa.controller;

import io.saasforge.iam.mfa.service.OperatorMfaService;
import io.saasforge.sdk.core.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

// 只接受 Platform Realm 的 Access Token（安全链保证），主体名称即 Operator 标识。登录中当场绑定走 /platform/auth/login/bind/*。
@Tag(name = "平台 MFA")
@RestController
class OperatorMfaController {
    private final OperatorMfaService mfa;

    OperatorMfaController(OperatorMfaService mfa) {
        this.mfa = mfa;
    }

    @Operation(summary = "开始绑定 Authenticator：返回供扫码的 otpauth URI，提交首个动态码后才生效")
    @PostMapping(value = "/platform/auth/mfa/bind/start", consumes = "application/json")
    ApiResponse<BindingStartResponse> startBinding(Authentication authentication) {
        return ApiResponse.ok(new BindingStartResponse(mfa.startBinding(operatorId(authentication))));
    }

    @Operation(summary = "验证旧 Authenticator 的动态码后开始更换，返回新的 otpauth URI；确认后旧 Authenticator 与 Recovery Code 作废")
    @PostMapping(value = "/platform/auth/mfa/replace/start", consumes = "application/json")
    ApiResponse<BindingStartResponse> startReplacement(
            Authentication authentication, @Valid @RequestBody CodeRequest body) {
        return ApiResponse.ok(new BindingStartResponse(mfa.startReplacement(operatorId(authentication), body.code())));
    }

    @Operation(summary = "以首个动态码确认绑定或更换，返回只展示这一次的 Recovery Code")
    @PostMapping(value = "/platform/auth/mfa/bind/confirm", consumes = "application/json")
    ApiResponse<RecoveryCodesResponse> confirmBinding(
            Authentication authentication, @Valid @RequestBody CodeRequest body) {
        return ApiResponse.created(
                new RecoveryCodesResponse(mfa.confirmBinding(operatorId(authentication), body.code())));
    }

    @Operation(summary = "验证动态码后重新生成 Recovery Code，旧的一组全部作废")
    @PostMapping(value = "/platform/auth/mfa/recovery-codes/regenerate", consumes = "application/json")
    ApiResponse<RecoveryCodesResponse> regenerateRecoveryCodes(
            Authentication authentication, @Valid @RequestBody CodeRequest body) {
        return ApiResponse.created(
                new RecoveryCodesResponse(mfa.regenerateRecoveryCodes(operatorId(authentication), body.code())));
    }

    @Operation(summary = "验证动态码后解绑 Authenticator，Recovery Code 一并作废；要求 Operator 使用 MFA 时拒绝，只能更换")
    @PostMapping(value = "/platform/auth/mfa/unbind", consumes = "application/json")
    ApiResponse<Void> unbind(Authentication authentication, @Valid @RequestBody CodeRequest body) {
        mfa.unbind(operatorId(authentication), body.code());
        return ApiResponse.ok();
    }

    private static UUID operatorId(Authentication authentication) {
        return UUID.fromString(authentication.getName());
    }

    record CodeRequest(@NotBlank @Pattern(regexp = "\\d{6}") String code) {
        @Override
        public String toString() {
            return "CodeRequest[redacted]";
        }
    }

    record BindingStartResponse(String otpauthUri) {
        @Override
        public String toString() {
            return "BindingStartResponse[redacted]";
        }
    }

    record RecoveryCodesResponse(List<String> recoveryCodes) {
        @Override
        public String toString() {
            return "RecoveryCodesResponse[redacted]";
        }
    }
}
