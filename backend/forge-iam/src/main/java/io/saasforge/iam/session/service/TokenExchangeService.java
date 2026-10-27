package io.saasforge.iam.session.service;

import com.nimbusds.jwt.SignedJWT;
import io.saasforge.iam.identity.service.IdentityService;
import io.saasforge.iam.security.config.TenantTokenParameters;
import io.saasforge.iam.security.service.TokenEndpointClient;
import io.saasforge.iam.session.model.TokenResponse;
import io.saasforge.sdk.core.api.ForgeException;
import java.text.ParseException;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.HttpClientErrorException;

@Service
public class TokenExchangeService {
    private final TokenEndpointClient tokenEndpoint;
    private final IdentityService identities;
    private final String redirectUri;

    TokenExchangeService(
            TokenEndpointClient tokenEndpoint,
            IdentityService identities,
            @Value("${forge.iam.redirect-uri:http://localhost:5174/}") String redirectUri) {
        this.tokenEndpoint = tokenEndpoint;
        this.identities = identities;
        this.redirectUri = redirectUri;
    }

    /** membershipId 为空时只允许身份恰有一个有效 Membership，否则由 Token 定制器拒绝。 */
    public TokenResponse code(String code, String verifier, UUID membershipId) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("code_verifier", verifier);
        form.add("redirect_uri", redirectUri);
        if (membershipId != null) form.add(TenantTokenParameters.MEMBERSHIP_ID, membershipId.toString());
        return exchange(form);
    }

    public TokenResponse refresh(String token) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", token);
        return exchange(form);
    }

    // 切换租户就是携带目标 Membership 的一次标准刷新：沿用同一授权的轮换、重放撤销与互斥（ADR-0002）。
    public TokenResponse switchTenant(String token, UUID membershipId) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", token);
        form.add(TenantTokenParameters.MEMBERSHIP_ID, membershipId.toString());
        return exchange(form);
    }

    private TokenResponse exchange(LinkedMultiValueMap<String, String> form) {
        Map<?, ?> result;
        try {
            result = tokenEndpoint.grant("tenant-app", form);
        } catch (HttpClientErrorException error) {
            Map<?, ?> body = error.getResponseBodyAs(Map.class);
            if (body != null && OAuth2ErrorCodes.ACCESS_DENIED.equals(body.get("error"))) {
                throw ForgeException.withErrorMessage(
                        SessionErrorCode.TENANT_ACCESS_DENIED, "Membership is not selectable by this identity");
            }
            throw ForgeException.withErrorMessage(SessionErrorCode.AUTH_FAILED, "Token grant rejected");
        }
        try {
            String access = (String) result.get("access_token");
            // 只解析本进程标准 Token 端点的响应，外部输入不会进入此路径。
            var claims = SignedJWT.parse(access).getJWTClaimsSet();
            var identity = identities.findForRefresh(
                    UUID.fromString(claims.getSubject()),
                    UUID.fromString(claims.getStringClaim("membership_id")),
                    UUID.fromString(claims.getStringClaim("tenant_id")));
            if (identity == null) {
                throw ForgeException.withErrorMessage(SessionErrorCode.AUTH_FAILED, "Membership no longer active");
            }
            return new TokenResponse(
                    access,
                    (String) result.get("refresh_token"),
                    "Bearer",
                    ((Number) result.get("expires_in")).longValue(),
                    identity.identityName(),
                    identity.tenantId(),
                    identity.tenantName(),
                    identity.membershipId());
        } catch (ParseException error) {
            throw new IllegalStateException(error);
        }
    }
}
