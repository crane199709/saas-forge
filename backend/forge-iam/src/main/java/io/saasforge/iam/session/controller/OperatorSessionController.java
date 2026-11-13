package io.saasforge.iam.session.controller;

import io.saasforge.iam.mfa.model.BindingStartResponse;
import io.saasforge.iam.mfa.model.RecoveryCodeFormat;
import io.saasforge.iam.session.model.OperatorTokenResponse;
import io.saasforge.iam.session.model.PendingOperatorMfa;
import io.saasforge.iam.session.service.LoginAudit;
import io.saasforge.iam.session.service.OperatorSessionService;
import io.saasforge.sdk.core.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Platform Realm 的认证接口，与 Tenant Realm 的 {@code /auth/*} 对称，签发 issuer 独立的 Operator Token（ADR-0003）。 */
@Tag(name = "平台登录会话")
@RestController
class OperatorSessionController {
    private final OperatorSessionService sessions;
    private final LoginAudit audits;

    OperatorSessionController(OperatorSessionService sessions, LoginAudit audits) {
        this.sessions = sessions;
        this.audits = audits;
    }

    @Operation(summary = "以密码认证 Operator；已绑定 Authenticator 时进入待 MFA，要求 MFA 而未绑定时进入必须绑定，否则建立登录会话")
    @PostMapping(value = "/platform/auth/login", consumes = "application/json")
    ApiResponse<LoginResponse> login(
            @Valid @RequestBody LoginRequest body, HttpServletRequest request, HttpServletResponse response) {
        var firstFactor = sessions.authenticate(body.account(), body.password());
        PendingOperatorMfa pending = sessions.pendingMfa(firstFactor).orElse(null);
        if (pending == null) {
            return ApiResponse.ok(signIn(firstFactor, null, request, response));
        }
        // 换新会话标识防会话固定，并清掉会话里原有的已认证主体：授权端点因此不认可此会话，直到完成第二步。
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }
        HttpSession session = request.getSession();
        session.removeAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        SecurityContextHolder.clearContext();
        session.setAttribute(PendingOperatorMfa.SESSION_ATTRIBUTE, pending);
        return ApiResponse.ok(
                new LoginResponse(pending.bindRequired() ? LoginResponse.NEXT_BIND : LoginResponse.NEXT_MFA));
    }

    @Operation(summary = "在待 MFA 状态下提交动态码或 Recovery Code，通过后建立登录会话")
    @PostMapping(value = "/platform/auth/login/mfa", consumes = "application/json")
    ApiResponse<LoginResponse> submitMfa(
            @Valid @RequestBody MfaRequest body, HttpServletRequest request, HttpServletResponse response) {
        var authentication = sessions.completeMfa(pending(request), body.code(), body.recoveryCode());
        return ApiResponse.ok(signIn(authentication, null, request, response));
    }

    @Operation(summary = "在必须绑定状态下开始绑定 Authenticator，返回供扫码的 otpauth URI")
    @PostMapping(value = "/platform/auth/login/bind/start", consumes = "application/json")
    ApiResponse<BindingStartResponse> startLoginBinding(HttpServletRequest request) {
        return ApiResponse.ok(new BindingStartResponse(sessions.startLoginBinding(pending(request))));
    }

    @Operation(summary = "在必须绑定状态下以首个动态码确认绑定，绑定生效即建立登录会话，并返回只展示这一次的 Recovery Code")
    @PostMapping(value = "/platform/auth/login/bind/confirm", consumes = "application/json")
    ApiResponse<LoginResponse> confirmLoginBinding(
            @Valid @RequestBody TotpRequest body, HttpServletRequest request, HttpServletResponse response) {
        var bound = sessions.confirmLoginBinding(pending(request), body.code());
        return ApiResponse.created(signIn(bound.authentication(), bound.recoveryCodes(), request, response));
    }

    private static PendingOperatorMfa pending(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session == null ? null : (PendingOperatorMfa) session.getAttribute(PendingOperatorMfa.SESSION_ATTRIBUTE);
    }

    private LoginResponse signIn(
            Authentication authentication,
            List<String> recoveryCodes,
            HttpServletRequest request,
            HttpServletResponse response) {
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        new HttpSessionSecurityContextRepository().saveContext(context, request, response);
        sessions.registerLoginSession(
                authentication.getName(), request.getSession().getId());
        request.getSession().removeAttribute(PendingOperatorMfa.SESSION_ATTRIBUTE);
        return new LoginResponse(
                LoginResponse.NEXT_AUTHORIZE, "platform-console", sessions.redirectUri(), recoveryCodes);
    }

    @Operation(summary = "退出当前浏览器的 IAM 登录会话，不吊销已签发的 Token")
    @PostMapping(value = "/platform/auth/logout", consumes = "application/json")
    ApiResponse<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        new SecurityContextLogoutHandler().logout(request, response, authentication);
        audits.loggedOut(authentication);
        return ApiResponse.ok();
    }

    @Operation(summary = "以授权码和 PKCE 校验值交换 Platform Realm Token")
    @PostMapping(value = "/platform/auth/exchange", consumes = "application/json")
    ApiResponse<OperatorTokenResponse> exchange(@Valid @RequestBody CodeRequest request) {
        return ApiResponse.ok(sessions.code(request.code(), request.codeVerifier()));
    }

    @Operation(summary = "通过标准 refresh_token grant 轮换 Platform Realm 刷新凭证")
    @PostMapping(value = "/platform/auth/refresh", consumes = "application/json")
    ApiResponse<OperatorTokenResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ApiResponse.ok(sessions.refresh(request.refreshToken()));
    }

    /**
     * 登录响应。
     *
     * @param nextStep {@code authorize}：已认证，可走授权码流程；{@code mfa}：须提交动态码或 Recovery Code；{@code bind}：须当场绑定
     *     Authenticator。后两者其余字段为空
     * @param recoveryCodes 仅当场绑定完成时返回，只展示这一次
     */
    record LoginResponse(String nextStep, String clientId, String redirectUri, List<String> recoveryCodes) {
        static final String NEXT_AUTHORIZE = "authorize";
        static final String NEXT_MFA = "mfa";
        static final String NEXT_BIND = "bind";

        LoginResponse(String nextStep) {
            this(nextStep, null, null, null);
        }

        @Override
        public String toString() {
            return "LoginResponse[nextStep=" + nextStep + "]";
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

    record TotpRequest(@NotBlank @Pattern(regexp = "\\d{6}") String code) {
        @Override
        public String toString() {
            return "TotpRequest[redacted]";
        }
    }

    record LoginRequest(
            @NotBlank @Size(max = 120) String account,
            @NotBlank @Size(max = 72) String password) {
        @Override
        public String toString() {
            return "LoginRequest[redacted]";
        }
    }

    record CodeRequest(
            @NotBlank @Size(max = 512) String code,
            @NotBlank @Size(min = 43, max = 128) String codeVerifier) {
        @Override
        public String toString() {
            return "CodeRequest[redacted]";
        }
    }

    record RefreshRequest(@NotBlank @Size(max = 512) String refreshToken) {
        @Override
        public String toString() {
            return "RefreshRequest[redacted]";
        }
    }
}
