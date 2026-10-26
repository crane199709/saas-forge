package io.saasforge.iam.session.service;

import com.nimbusds.jwt.SignedJWT;
import io.saasforge.iam.identity.service.IdentityService;
import io.saasforge.iam.security.config.TenantClientCredentials;
import io.saasforge.iam.session.model.TokenResponse;
import java.text.ParseException;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class TokenExchangeService {
    private final ServletWebServerApplicationContext application;
    private final TenantClientCredentials credentials;
    private final IdentityService identities;
    private final String redirectUri;
    private final ServerProperties server;
    private final String tokenBaseUri;

    TokenExchangeService(
            ServletWebServerApplicationContext application,
            TenantClientCredentials credentials,
            IdentityService identities,
            @Value("${forge.iam.redirect-uri:http://localhost:5174/}") String redirectUri,
            ServerProperties server,
            @Value("${forge.iam.token-base-uri:}") String tokenBaseUri) {
        this.application = application;
        this.credentials = credentials;
        this.identities = identities;
        this.redirectUri = redirectUri;
        this.server = server;
        this.tokenBaseUri = tokenBaseUri;
    }

    public TokenResponse code(String code, String verifier) {
        return exchange(Map.of(
                "grant_type",
                "authorization_code",
                "code",
                code,
                "code_verifier",
                verifier,
                "redirect_uri",
                redirectUri));
    }

    public TokenResponse refresh(String token) {
        return exchange(Map.of("grant_type", "refresh_token", "refresh_token", token));
    }

    private TokenResponse exchange(Map<String, String> parameters) {
        var form = new LinkedMultiValueMap<String, String>();
        parameters.forEach(form::add);
        Map<?, ?> result;
        try {
            // 地址只来自服务端配置，不能由请求头或客户端指定；所有 grant 仍由标准过滤器校验。
            result = RestClient.create(tokenBaseUri())
                    .post()
                    .uri("/oauth2/token")
                    .headers(headers -> headers.setBasicAuth("tenant-app", credentials.secret()))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(Map.class);
        } catch (HttpClientErrorException error) {
            throw new BadCredentialsException("auth.failed");
        }
        try {
            String access = (String) result.get("access_token");
            // 只解析本进程标准 Token 端点的响应，外部输入不会进入此路径。
            var claims = SignedJWT.parse(access).getJWTClaimsSet();
            var identity = identities.findForRefresh(
                    UUID.fromString(claims.getSubject()),
                    UUID.fromString(claims.getStringClaim("membership_id")),
                    UUID.fromString(claims.getStringClaim("tenant_id")));
            if (identity == null) throw new BadCredentialsException("auth.failed");
            return new TokenResponse(
                    access,
                    (String) result.get("refresh_token"),
                    "Bearer",
                    ((Number) result.get("expires_in")).longValue(),
                    identity.identityName(),
                    identity.tenantName());
        } catch (ParseException error) {
            throw new IllegalStateException(error);
        }
    }

    private String tokenBaseUri() {
        if (!tokenBaseUri.isBlank()) return tokenBaseUri;
        var address = server.getAddress();
        String host = address == null || address.isAnyLocalAddress() ? "127.0.0.1" : address.getHostAddress();
        // IPv6 字面量必须用方括号区分地址内的冒号与端口分隔符。
        if (host.contains(":")) host = "[" + host + "]";
        boolean tls = server.getSsl() != null && server.getSsl().isEnabled();
        return UriComponentsBuilder.newInstance()
                .scheme(tls ? "https" : "http")
                .host(host)
                .port(application.getWebServer().getPort())
                .path(server.getServlet().getContextPath())
                .build()
                .toUriString();
    }
}
