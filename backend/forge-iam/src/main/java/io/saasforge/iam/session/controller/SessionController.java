package io.saasforge.iam.session.controller;

import io.saasforge.iam.security.config.IamProperties;
import io.saasforge.iam.session.model.TokenResponse;
import io.saasforge.iam.session.service.LoginAudit;
import io.saasforge.iam.session.service.TenantSessionService;
import io.saasforge.iam.session.service.TokenExchangeService;
import io.saasforge.iam.verification.model.CodeDelivery;
import io.saasforge.iam.verification.model.SendCodeRequest;
import io.saasforge.sdk.core.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class SessionController {
    private final TenantSessionService sessions;
    private final TokenExchangeService tokens;
    private final LoginAudit audits;
    private final String redirectUri;

    SessionController(
            TenantSessionService sessions, TokenExchangeService tokens, LoginAudit audits, IamProperties properties) {
        this.sessions = sessions;
        this.tokens = tokens;
        this.audits = audits;
        this.redirectUri = properties.redirectUri();
    }

    @Operation(summary = "认证身份并建立标准授权流程的登录会话")
    @PostMapping(value = "/auth/login", consumes = "application/json")
    ApiResponse<LoginResponse> login(
            @Valid @RequestBody LoginRequest body, HttpServletRequest request, HttpServletResponse response) {
        return ApiResponse.ok(signIn(sessions.authenticate(body.account(), body.password()), request, response));
    }

    @Operation(summary = "向邮箱或手机号发送登录验证码，目标是否已注册的响应相同")
    @PostMapping(value = "/auth/login/send-code", consumes = "application/json")
    ApiResponse<CodeDelivery> sendLoginCode(@Valid @RequestBody SendCodeRequest body) {
        return ApiResponse.ok(sessions.sendLoginCode(body.target()));
    }

    @Operation(summary = "以邮箱或手机验证码认证身份，建立与密码登录相同的登录会话")
    @PostMapping(value = "/auth/code-login", consumes = "application/json")
    ApiResponse<LoginResponse> codeLogin(
            @Valid @RequestBody CodeLoginRequest body, HttpServletRequest request, HttpServletResponse response) {
        return ApiResponse.ok(signIn(sessions.authenticateByCode(body.target(), body.code()), request, response));
    }

    private LoginResponse signIn(
            Authentication authentication, HttpServletRequest request, HttpServletResponse response) {
        if (request.getSession(false) != null) request.changeSessionId();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        new HttpSessionSecurityContextRepository().saveContext(context, request, response);
        // 凭证（密码或验证码）校验通过后才返回可进入的租户，由前端决定自动进入、选择租户或展示无租户页。
        var memberships = toMembershipResponses(UUID.fromString(authentication.getName()));
        return new LoginResponse("tenant-app", redirectUri, memberships);
    }

    @Operation(summary = "列出当前 Access Token 所属身份可进入的有效 Membership")
    @GetMapping("/auth/memberships")
    ApiResponse<List<MembershipResponse>> memberships(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(toMembershipResponses(UUID.fromString(jwt.getSubject())));
    }

    @Operation(summary = "用当前刷新凭证换发绑定目标 Membership 的 Token，旧刷新凭证随之轮换失效")
    @PostMapping(value = "/auth/switch", consumes = "application/json")
    ApiResponse<TokenResponse> switchTenant(@Valid @RequestBody SwitchRequest request) {
        return ApiResponse.ok(tokens.switchTenant(request.refreshToken(), request.membershipId()));
    }

    private List<MembershipResponse> toMembershipResponses(UUID identityId) {
        return sessions.memberships(identityId).stream()
                .map(membership -> new MembershipResponse(
                        membership.membershipId(), membership.tenantId(), membership.tenantName()))
                .toList();
    }

    @Operation(summary = "退出当前浏览器的 IAM 登录会话，不吊销已签发的 Token")
    @PostMapping(value = "/auth/logout", consumes = "application/json")
    ApiResponse<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        // 仅接受同源 JSON 请求；同时销毁 Redis 会话和当前请求的安全上下文。
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        new SecurityContextLogoutHandler().logout(request, response, authentication);
        audits.loggedOut(authentication);
        return ApiResponse.ok();
    }

    @Operation(summary = "以授权码和 PKCE 校验值交换 Token，匿名适配器不认证浏览器客户端")
    @PostMapping(value = "/auth/exchange", consumes = "application/json")
    ApiResponse<TokenResponse> exchange(@Valid @RequestBody CodeRequest request) {
        return ApiResponse.ok(tokens.code(request.code(), request.codeVerifier(), request.membershipId()));
    }

    @Operation(summary = "通过标准 refresh_token grant 轮换刷新凭证")
    @PostMapping(value = "/auth/refresh", consumes = "application/json")
    ApiResponse<TokenResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ApiResponse.ok(tokens.refresh(request.refreshToken()));
    }

    record LoginResponse(String clientId, String redirectUri, List<MembershipResponse> memberships) {}

    record MembershipResponse(UUID membershipId, UUID tenantId, String tenantName) {}

    record LoginRequest(
            @NotBlank @Size(max = 254) String account,
            @NotBlank @Size(max = 72) String password) {
        @Override
        public String toString() {
            return "LoginRequest[redacted]";
        }
    }

    record CodeLoginRequest(
            @NotBlank @Size(max = 254) @Pattern(regexp = SendCodeRequest.TARGET_PATTERN)
            String target,

            @NotBlank @Pattern(regexp = SendCodeRequest.CODE_PATTERN)
            String code) {
        @Override
        public String toString() {
            return "CodeLoginRequest[redacted]";
        }
    }

    record CodeRequest(
            @NotBlank @Size(max = 512) String code,
            @NotBlank @Size(min = 43, max = 128) String codeVerifier,
            UUID membershipId) {
        @Override
        public String toString() {
            return "CodeRequest[redacted]";
        }
    }

    record SwitchRequest(
            @NotBlank @Size(max = 512) String refreshToken,
            @NotNull UUID membershipId) {
        @Override
        public String toString() {
            return "SwitchRequest[membershipId=" + membershipId + "]";
        }
    }

    record RefreshRequest(@NotBlank @Size(max = 512) String refreshToken) {
        @Override
        public String toString() {
            return "RefreshRequest[redacted]";
        }
    }
}
