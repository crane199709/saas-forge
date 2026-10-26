package io.saasforge.iam.session.controller;

import io.saasforge.iam.session.model.TokenResponse;
import io.saasforge.iam.session.service.TenantSessionService;
import io.saasforge.iam.session.service.TokenExchangeService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
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
        // 密码校验通过后才返回可进入的租户，由前端决定自动进入、选择租户或展示无租户页。
        var memberships = toMembershipResponses(UUID.fromString(authentication.getName()));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new LoginResponse("tenant-app", redirectUri, memberships));
    }

    @Operation(summary = "列出当前 Access Token 所属身份可进入的有效 Membership")
    @GetMapping("/auth/memberships")
    ResponseEntity<List<MembershipResponse>> memberships(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(toMembershipResponses(UUID.fromString(jwt.getSubject())));
    }

    @Operation(summary = "用当前刷新凭证换发绑定目标 Membership 的 Token，旧刷新凭证随之轮换失效")
    @PostMapping(value = "/auth/switch", consumes = "application/json")
    ResponseEntity<TokenResponse> switchTenant(@Valid @RequestBody SwitchRequest request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(tokens.switchTenant(request.refreshToken(), request.membershipId()));
    }

    private List<MembershipResponse> toMembershipResponses(UUID identityId) {
        return sessions.memberships(identityId).stream()
                .map(membership -> new MembershipResponse(
                        membership.membershipId(), membership.tenantId(), membership.tenantName()))
                .toList();
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
                .body(tokens.code(request.code(), request.codeVerifier(), request.membershipId()));
    }

    @Operation(summary = "通过标准 refresh_token grant 轮换刷新凭证")
    @PostMapping(value = "/auth/refresh", consumes = "application/json")
    ResponseEntity<TokenResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(tokens.refresh(request.refreshToken()));
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
