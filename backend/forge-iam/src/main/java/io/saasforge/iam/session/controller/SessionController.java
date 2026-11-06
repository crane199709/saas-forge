package io.saasforge.iam.session.controller;

import io.saasforge.iam.mfa.model.RecoveryCodeFormat;
import io.saasforge.iam.security.config.IamProperties;
import io.saasforge.iam.session.model.PendingMfa;
import io.saasforge.iam.session.model.TokenResponse;
import io.saasforge.iam.session.service.LoginAudit;
import io.saasforge.iam.session.service.TenantSessionService;
import io.saasforge.iam.session.service.TokenExchangeService;
import io.saasforge.iam.verification.model.CodeDelivery;
import io.saasforge.iam.verification.model.SendCodeRequest;
import io.saasforge.sdk.core.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
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

@Tag(name = "登录会话")
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

    @Operation(summary = "以密码认证身份并建立标准授权流程的登录会话；已绑定 Authenticator 时先进入待 MFA 状态")
    @PostMapping(value = "/auth/login", consumes = "application/json")
    ApiResponse<LoginResponse> login(
            @Valid @RequestBody LoginRequest body, HttpServletRequest request, HttpServletResponse response) {
        return ApiResponse.ok(
                firstFactorPassed(sessions.authenticate(body.account(), body.password()), request, response));
    }

    @Operation(summary = "向邮箱或手机号发送登录验证码，目标是否已注册的响应相同")
    @PostMapping(value = "/auth/login/send-code", consumes = "application/json")
    ApiResponse<CodeDelivery> sendLoginCode(@Valid @RequestBody SendCodeRequest body) {
        return ApiResponse.ok(sessions.sendLoginCode(body.target()));
    }

    @Operation(summary = "以邮箱或手机验证码认证身份，与密码登录一样建立登录会话或进入待 MFA 状态")
    @PostMapping(value = "/auth/code-login", consumes = "application/json")
    ApiResponse<LoginResponse> codeLogin(
            @Valid @RequestBody CodeLoginRequest body, HttpServletRequest request, HttpServletResponse response) {
        return ApiResponse.ok(
                firstFactorPassed(sessions.authenticateByCode(body.target(), body.code()), request, response));
    }

    @Operation(summary = "在待 MFA 状态下提交动态码或 Recovery Code，通过后建立登录会话，响应与未绑定时的登录相同")
    @PostMapping(value = "/auth/login/mfa", consumes = "application/json")
    ApiResponse<LoginResponse> submitMfa(
            @Valid @RequestBody MfaRequest body, HttpServletRequest request, HttpServletResponse response) {
        HttpSession session = request.getSession(false);
        PendingMfa pending = session == null ? null : (PendingMfa) session.getAttribute(PendingMfa.SESSION_ATTRIBUTE);
        Authentication authentication = sessions.completeMfa(pending, body.code(), body.recoveryCode());
        return ApiResponse.ok(signIn(authentication, request, response));
    }

    private LoginResponse firstFactorPassed(
            Authentication firstFactor, HttpServletRequest request, HttpServletResponse response) {
        PendingMfa pending = sessions.pendingMfa(firstFactor).orElse(null);
        if (pending == null) return signIn(firstFactor, request, response);
        // 待 MFA：换新会话标识防会话固定，并清掉会话里原有的已认证主体，授权端点因此不认可此会话，直到完成 MFA。
        if (request.getSession(false) != null) request.changeSessionId();
        HttpSession session = request.getSession();
        session.removeAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        SecurityContextHolder.clearContext();
        session.setAttribute(PendingMfa.SESSION_ATTRIBUTE, pending);
        // 完成 MFA 之前不返回可进入的租户。
        return new LoginResponse(LoginResponse.NEXT_MFA, null, null, null);
    }

    private LoginResponse signIn(
            Authentication authentication, HttpServletRequest request, HttpServletResponse response) {
        if (request.getSession(false) != null) request.changeSessionId();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        new HttpSessionSecurityContextRepository().saveContext(context, request, response);
        request.getSession().removeAttribute(PendingMfa.SESSION_ATTRIBUTE);
        sessions.registerLoginSession(
                authentication.getName(), request.getSession().getId());
        // 认证完成后才返回可进入的租户，由前端决定自动进入、选择租户或展示无租户页。
        var memberships = toMembershipResponses(UUID.fromString(authentication.getName()));
        return new LoginResponse(LoginResponse.NEXT_AUTHORIZE, "tenant-app", redirectUri, memberships);
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

    /**
     * 登录响应。
     *
     * @param nextStep {@code authorize}：已认证，可走授权码流程；{@code mfa}：待 MFA，须提交动态码或 Recovery Code，其余字段为空
     */
    record LoginResponse(String nextStep, String clientId, String redirectUri, List<MembershipResponse> memberships) {
        static final String NEXT_AUTHORIZE = "authorize";
        static final String NEXT_MFA = "mfa";
    }

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

    /** 动态码与 Recovery Code 恰好提供一个；Recovery Code 忽略大小写、空格与连字符。 */
    record MfaRequest(
            @Pattern(regexp = "\\d{6}") String code,
            @RecoveryCodeFormat String recoveryCode) {
        @AssertTrue
        public boolean isExactlyOneFactor() {
            return (code == null) != (recoveryCode == null);
        }

        @Override
        public String toString() {
            return "MfaRequest[redacted]";
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
