package io.saasforge.iam.session.controller;

import io.saasforge.iam.session.model.OperatorTokenResponse;
import io.saasforge.iam.session.service.LoginAudit;
import io.saasforge.iam.session.service.OperatorSessionService;
import io.saasforge.sdk.core.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Platform Realm 的认证接口，与 Tenant Realm 的 {@code /auth/*} 对称，签发 issuer 独立的 Operator Token（ADR-0003）。 */
@RestController
class OperatorSessionController {
    private final OperatorSessionService sessions;
    private final LoginAudit audits;

    OperatorSessionController(OperatorSessionService sessions, LoginAudit audits) {
        this.sessions = sessions;
        this.audits = audits;
    }

    @Operation(summary = "认证 Operator 并建立标准授权流程的登录会话")
    @PostMapping(value = "/platform/auth/login", consumes = "application/json")
    ApiResponse<LoginResponse> login(
            @Valid @RequestBody LoginRequest body, HttpServletRequest request, HttpServletResponse response) {
        var authentication = sessions.authenticate(body.account(), body.password());
        if (request.getSession(false) != null) request.changeSessionId();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        new HttpSessionSecurityContextRepository().saveContext(context, request, response);
        return ApiResponse.ok(new LoginResponse("platform-console", sessions.redirectUri()));
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

    record LoginResponse(String clientId, String redirectUri) {}

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
