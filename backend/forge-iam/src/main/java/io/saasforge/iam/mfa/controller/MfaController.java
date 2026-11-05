package io.saasforge.iam.mfa.controller;

import io.saasforge.iam.mfa.service.IdentityMfaService;
import io.saasforge.sdk.core.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

// 会话主体与 Access Token 的名称都是 Identity id，由安全链保证只属于 Tenant Realm。
@Tag(name = "MFA")
@RestController
class MfaController {
    private final IdentityMfaService mfa;

    MfaController(IdentityMfaService mfa) {
        this.mfa = mfa;
    }

    @Operation(summary = "开始绑定 Authenticator：返回供扫码的 otpauth URI，提交首个动态码后才生效；接受 Access Token 或登录会话")
    @PostMapping(value = "/auth/mfa/bind/start", consumes = "application/json")
    ApiResponse<BindingStartResponse> startBinding(Authentication authentication) {
        return ApiResponse.ok(new BindingStartResponse(mfa.startBinding(identityId(authentication))));
    }

    @Operation(summary = "以首个动态码确认绑定，返回只展示这一次的 Recovery Code；接受 Access Token 或登录会话，确认后当前授权或会话视为已完成 MFA")
    @PostMapping(value = "/auth/mfa/bind/confirm", consumes = "application/json")
    ApiResponse<RecoveryCodesResponse> confirmBinding(
            Authentication authentication,
            @Valid @RequestBody CodeRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        UUID identityId = identityId(authentication);
        List<String> codes = mfa.confirmBinding(identityId, body.code());
        // 首个动态码已在此验证，当前会话或授权即视为完成 MFA，被 MFA Requirement 拦下的身份随后可重试进入（ADR-0019）。
        if (authentication instanceof JwtAuthenticationToken jwt) {
            mfa.completeAuthorization(identityId, jwt.getToken().getTokenValue());
        } else {
            // 会话权限提升，换发会话标识防会话固定；重新走授权码流程即得到记为已完成 MFA 的授权。
            request.changeSessionId();
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(mfa.completeSession(authentication));
            SecurityContextHolder.setContext(context);
            new HttpSessionSecurityContextRepository().saveContext(context, request, response);
        }
        return ApiResponse.created(new RecoveryCodesResponse(codes));
    }

    @Operation(summary = "验证动态码后重新生成 Recovery Code，旧的一组全部作废")
    @PostMapping(value = "/auth/mfa/recovery-codes/regenerate", consumes = "application/json")
    ApiResponse<RecoveryCodesResponse> regenerateRecoveryCodes(
            Authentication authentication, @Valid @RequestBody CodeRequest body) {
        return ApiResponse.created(
                new RecoveryCodesResponse(mfa.regenerateRecoveryCodes(identityId(authentication), body.code())));
    }

    @Operation(summary = "验证动态码后解绑 Authenticator，Recovery Code 一并作废")
    @PostMapping(value = "/auth/mfa/unbind", consumes = "application/json")
    ApiResponse<Void> unbind(Authentication authentication, @Valid @RequestBody CodeRequest body) {
        mfa.unbind(identityId(authentication), body.code());
        return ApiResponse.ok();
    }

    private static UUID identityId(Authentication authentication) {
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
