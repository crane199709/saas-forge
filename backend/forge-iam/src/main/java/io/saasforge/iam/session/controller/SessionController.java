package io.saasforge.iam.session.controller;

import io.saasforge.iam.session.model.TokenResponse;
import io.saasforge.iam.session.service.TenantSessionService;
import io.saasforge.iam.session.service.TokenExchangeService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class SessionController {
    private final TenantSessionService sessions;
    private final TokenExchangeService tokens;
    private final String redirectUri;

    SessionController(
            TenantSessionService sessions,
            TokenExchangeService tokens,
            @Value("${forge.iam.redirect-uri:http://localhost:5174/}") String redirectUri) {
        this.sessions = sessions;
        this.tokens = tokens;
        this.redirectUri = redirectUri;
    }

    @Operation(summary = "认证身份并建立标准授权流程的登录会话")
    @PostMapping(value = "/auth/login", consumes = "application/json")
    ResponseEntity<LoginResponse> login(
            @Valid @RequestBody LoginRequest body, HttpServletRequest request, HttpServletResponse response) {
        var authentication = sessions.authenticate(body.account(), body.password());
        if (request.getSession(false) != null) request.changeSessionId();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        new HttpSessionSecurityContextRepository().saveContext(context, request, response);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new LoginResponse("tenant-app", redirectUri));
    }

    @Operation(summary = "退出当前浏览器的 IAM 登录会话，不吊销已签发的 Token")
    @PostMapping(value = "/auth/logout", consumes = "application/json")
    ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        // 仅接受同源 JSON 请求；同时销毁 Redis 会话和当前请求的安全上下文。
        new SecurityContextLogoutHandler()
                .logout(request, response, SecurityContextHolder.getContext().getAuthentication());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @Operation(summary = "以授权码和 PKCE 校验值交换 Token，匿名适配器不认证浏览器客户端")
    @PostMapping(value = "/auth/exchange", consumes = "application/json")
    ResponseEntity<TokenResponse> exchange(@Valid @RequestBody CodeRequest request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(tokens.code(request.code(), request.codeVerifier()));
    }

    @Operation(summary = "通过标准 refresh_token grant 轮换刷新凭证")
    @PostMapping(value = "/auth/refresh", consumes = "application/json")
    ResponseEntity<TokenResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(tokens.refresh(request.refreshToken()));
    }

    record LoginResponse(String clientId, String redirectUri) {}

    record LoginRequest(
            @NotBlank @Size(max = 254) String account,
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
