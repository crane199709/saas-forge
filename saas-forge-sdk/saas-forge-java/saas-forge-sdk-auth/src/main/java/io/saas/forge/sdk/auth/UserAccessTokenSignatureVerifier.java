package io.saas.forge.sdk.auth;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 验证 IAM User Access Token 的签名和固定 Claim 形态，不替调用方决定撤销失败语义。 */
public final class UserAccessTokenSignatureVerifier {
    private static final Set<String> PLATFORM_CLAIMS = Set.of(
            "iss", "aud", "iat", "exp", "identityId", "jti");
    private static final Set<String> TENANT_CLAIMS = Set.of(
            "iss", "aud", "iat", "exp", "identityId", "membershipId", "tenantId", "jti");

    private final ServiceJwtVerificationKeyResolver keys;
    private final Clock clock;
    private final String issuer;
    private final String audience;
    private final Duration clockSkew;
    private final boolean requireConsoleProtocol;

    public UserAccessTokenSignatureVerifier(
            ServiceJwtVerificationKeyResolver keys,
            Clock clock,
            String issuer,
            String audience,
            Duration clockSkew) {
        this(keys, clock, issuer, audience, clockSkew, false);
    }

    public UserAccessTokenSignatureVerifier(ServiceJwtVerificationKeyResolver keys, Clock clock,
            String issuer, String audience, Duration clockSkew, boolean requireConsoleProtocol) {
        this.requireConsoleProtocol = requireConsoleProtocol;
        if (keys == null || clock == null
                || issuer == null || issuer.isBlank()
                || audience == null || audience.isBlank()
                || clockSkew == null || clockSkew.isNegative()) {
            throw new IllegalArgumentException("User Access Token 验签配置不合法");
        }
        this.keys = keys;
        this.clock = clock;
        this.issuer = issuer;
        this.audience = audience;
        this.clockSkew = clockSkew;
    }

    public VerifiedUserAccessTokenClaims verify(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")
                || authorization.length() == "Bearer ".length()) {
            throw new UserAccessTokenInvalidException();
        }
        try {
            SignedJWT jwt = SignedJWT.parse(authorization.substring("Bearer ".length()));
            if (!JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm())
                    || !JOSEObjectType.JWT.equals(jwt.getHeader().getType())
                    || jwt.getHeader().getKeyID() == null) {
                throw new UserAccessTokenInvalidException();
            }
            ServiceJwtVerificationKey key = keys.findByKid(jwt.getHeader().getKeyID())
                    .orElseThrow(UserAccessTokenInvalidException::new);
            RSAKey rsaKey = new RSAKey.Builder(new Base64URL(key.modulus()), new Base64URL(key.exponent()))
                    .keyID(key.kid())
                    .build();
            if (!jwt.verify(new RSASSAVerifier(rsaKey))) {
                throw new UserAccessTokenInvalidException();
            }
            return claims(jwt, key.kid());
        } catch (UserAccessTokenInvalidException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new UserAccessTokenInvalidException(exception);
        }
    }

    private VerifiedUserAccessTokenClaims claims(SignedJWT jwt, String kid) throws Exception {
        var claims = jwt.getJWTClaimsSet();
        Set<String> names = new java.util.HashSet<>(claims.getClaims().keySet());
        boolean console = "CONSOLE_V2".equals(claims.getStringClaim("sessionProtocol"));
        if (requireConsoleProtocol && !console) throw new UserAccessTokenInvalidException();
        if (console) {
            canonicalUuidV7(claims.getStringClaim("sessionId"));
            String version = claims.getStringClaim("contextVersion");
            if (version == null || !version.matches("0|[1-9][0-9]*")) throw new UserAccessTokenInvalidException();
            Long.parseLong(version);
            names.removeAll(Set.of("sessionProtocol", "sessionId", "contextVersion"));
        }
        if (!names.equals(PLATFORM_CLAIMS) && !names.equals(TENANT_CLAIMS)
                || !issuer.equals(claims.getIssuer())
                || !List.of(audience).equals(claims.getAudience())) {
            throw new UserAccessTokenInvalidException();
        }
        Instant issuedAt = claims.getIssueTime().toInstant();
        Instant expiresAt = claims.getExpirationTime().toInstant();
        Instant now = clock.instant();
        if (issuedAt.isAfter(now.plus(clockSkew))
                || !expiresAt.plus(clockSkew).isAfter(now)
                || !expiresAt.isAfter(issuedAt)) {
            throw new UserAccessTokenInvalidException();
        }
        UUID identityId = canonicalUuidV7(claims.getStringClaim("identityId"));
        UUID jti = canonicalUuidV7(claims.getJWTID());
        if (names.equals(PLATFORM_CLAIMS)) {
            return new VerifiedUserAccessTokenClaims(
                    identityId, jti, kid, issuedAt, expiresAt, null, null);
        }
        return new VerifiedUserAccessTokenClaims(
                identityId,
                jti,
                kid,
                issuedAt,
                expiresAt,
                canonicalUuidV7(claims.getStringClaim("membershipId")),
                canonicalUuidV7(claims.getStringClaim("tenantId")));
    }

    private static UUID canonicalUuidV7(String value) {
        UUID id = UUID.fromString(value);
        if (id.version() != 7 || !id.toString().equals(value)) {
            throw new UserAccessTokenInvalidException();
        }
        return id;
    }
}
