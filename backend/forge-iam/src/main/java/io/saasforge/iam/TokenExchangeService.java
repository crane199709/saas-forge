package io.saasforge.iam;

import com.nimbusds.jwt.SignedJWT;
import java.text.ParseException;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

@Service
class TokenExchangeService {
    private final ServletWebServerApplicationContext application;
    private final TenantClientCredentials credentials;
    private final IdentityMapper identities;
    private final String redirectUri;

    TokenExchangeService(
            ServletWebServerApplicationContext application,
            TenantClientCredentials credentials,
            IdentityMapper identities,
            @Value("${forge.iam.redirect-uri:http://localhost:5174/}") String redirectUri) {
        this.application = application;
        this.credentials = credentials;
        this.identities = identities;
        this.redirectUri = redirectUri;
    }

    TokenResponse code(String code, String verifier) {
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

    TokenResponse refresh(String token) {
        return exchange(Map.of("grant_type", "refresh_token", "refresh_token", token));
    }

    private TokenResponse exchange(Map<String, String> parameters) {
        var form = new LinkedMultiValueMap<String, String>();
        parameters.forEach(form::add);
        Map<?, ?> result;
        try {
            // 固定回环地址，不能由请求头或客户端指定 Token 端点；所有 grant 由标准过滤器校验。
            result = RestClient.create(
                            "http://127.0.0.1:" + application.getWebServer().getPort())
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
}
