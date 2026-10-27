package io.saasforge.iam.security.service;

import io.saasforge.iam.security.config.PlatformSigningKey;
import io.saasforge.iam.security.config.RealmIssuerProperties;
import java.util.Map;
import org.springframework.security.oauth2.server.authorization.oidc.OidcProviderConfiguration;
import org.springframework.stereotype.Service;

/**
 * 两个 Realm 各自的 OIDC 发现元数据与 Platform Realm 的公钥（ADR-0003）。
 *
 * <p>两个 Realm 共用同一组授权与 Token 端点，以 issuer、JWKS 与 scope 区分；首版不签发 ID Token，scope 中没有 {@code openid}。
 */
@Service
public class RealmMetadataService {
    private final String tenantIssuer;
    private final String platformIssuer;
    private final PlatformSigningKey platformKey;

    RealmMetadataService(RealmIssuerProperties issuers, PlatformSigningKey platformKey) {
        this.tenantIssuer = issuers.issuer();
        this.platformIssuer = issuers.platform().issuer();
        this.platformKey = platformKey;
    }

    public Map<String, Object> tenantConfiguration() {
        return configuration(tenantIssuer, "tenant");
    }

    public Map<String, Object> platformConfiguration() {
        return configuration(platformIssuer, "platform");
    }

    public Map<String, Object> platformJwks() {
        return platformKey.publicKeys().toJSONObject();
    }

    private Map<String, Object> configuration(String issuer, String scope) {
        return OidcProviderConfiguration.builder()
                .issuer(issuer)
                .authorizationEndpoint(tenantIssuer + "/oauth2/authorize")
                .tokenEndpoint(tenantIssuer + "/oauth2/token")
                .tokenEndpointAuthenticationMethod("client_secret_basic")
                .jwkSetUrl(issuer + "/oauth2/jwks")
                .responseType("code")
                .grantType("authorization_code")
                .grantType("refresh_token")
                .codeChallengeMethod("S256")
                .subjectType("public")
                .idTokenSigningAlgorithm("RS256")
                .scope(scope)
                .build()
                .getClaims();
    }
}
