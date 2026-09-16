package io.saas.forge.sdk.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class UserAccessTokenVerifierTest {
    private static final Instant NOW = Instant.parse("2026-08-21T08:00:00Z");
    private static final UUID IDENTITY_ID = UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4c8f");
    private static final UUID JTI = UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4c90");
    private static RSAKey key;

    @BeforeAll
    static void generateKey() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("user-key").generate();
    }

    @Test
    void acceptsOnlyAnUnrevokedPlatformShapedToken() throws Exception {
        UserAccessTokenClaims claims = verifier((jti, kid) -> false)
                .verifyPlatformToken("Bearer " + token(Map.of(), NOW.minusSeconds(1), NOW.plusSeconds(899)));

        assertEquals(IDENTITY_ID, claims.identityId());
        assertEquals(JTI, claims.jti());
        assertEquals("user-key", claims.kid());
    }

    @Test
    void signatureVerifierReturnsPlatformOrTenantContextWithoutApplyingRevocationPolicy() throws Exception {
        UserAccessTokenSignatureVerifier verifier = signatureVerifier();

        VerifiedUserAccessTokenClaims platform = verifier.verify(
                "Bearer " + token(Map.of(), NOW.minusSeconds(1), NOW.plusSeconds(899)));
        VerifiedUserAccessTokenClaims tenant = verifier.verify("Bearer " + token(Map.of(
                "membershipId", IDENTITY_ID.toString(),
                "tenantId", JTI.toString()), NOW.minusSeconds(1), NOW.plusSeconds(899)));

        assertEquals(IDENTITY_ID, platform.identityId());
        assertEquals(null, platform.membershipId());
        assertEquals(null, platform.tenantId());
        assertEquals(IDENTITY_ID, tenant.membershipId());
        assertEquals(JTI, tenant.tenantId());
    }

    @Test
    void consoleCutoverRejectsLegacyAndMalformedConsoleClaims() throws Exception {
        var publicKey = new ServiceJwtVerificationKey(key.getKeyID(), key.getModulus().toString(), key.getPublicExponent().toString());
        var strict = new UserAccessTokenSignatureVerifier(kid -> Optional.of(publicKey),
                Clock.fixed(NOW, ZoneOffset.UTC), "https://iam.test", "saas.forge-api", Duration.ZERO, true);
        var console = Map.<String, Object>of("sessionProtocol", "CONSOLE_V2", "sessionId", JTI.toString(), "contextVersion", "0");
        assertEquals(IDENTITY_ID, strict.verify("Bearer " + token(console, NOW, NOW.plusSeconds(900))).identityId());
        assertThrows(UserAccessTokenInvalidException.class, () -> strict.verify("Bearer " + token(Map.of(), NOW, NOW.plusSeconds(900))));
        for (Object version : java.util.List.of("-1", "01", "9223372036854775808", 1)) {
            var invalid = new java.util.HashMap<>(console); invalid.put("contextVersion", version);
            assertThrows(UserAccessTokenInvalidException.class, () -> strict.verify("Bearer " + token(invalid, NOW, NOW.plusSeconds(900))));
        }
        for (String claim : console.keySet()) {
            var missing = new java.util.HashMap<>(console); missing.remove(claim);
            assertThrows(UserAccessTokenInvalidException.class, () -> strict.verify("Bearer " + token(missing, NOW, NOW.plusSeconds(900))));
        }
        var invalidId = new java.util.HashMap<>(console); invalidId.put("sessionId", UUID.randomUUID().toString());
        assertThrows(UserAccessTokenInvalidException.class, () -> strict.verify("Bearer " + token(invalidId, NOW, NOW.plusSeconds(900))));
    }

    @Test
    void rejectsTenantRoleAndPermissionClaims() throws Exception {
        UserAccessTokenVerifier verifier = verifier((jti, kid) -> false);

        assertThrows(UserAccessTokenInvalidException.class, () -> verifier.verifyPlatformToken(
                "Bearer " + token(Map.of("membershipId", IDENTITY_ID.toString(),
                        "tenantId", IDENTITY_ID.toString()), NOW, NOW.plusSeconds(900))));
        assertThrows(UserAccessTokenInvalidException.class, () -> verifier.verifyPlatformToken(
                "Bearer " + token(Map.of("role", "PLATFORM_ADMIN"), NOW, NOW.plusSeconds(900))));
        assertThrows(UserAccessTokenInvalidException.class, () -> verifier.verifyPlatformToken(
                "Bearer " + token(Map.of("permissions", java.util.List.of("tenant:create")),
                        NOW, NOW.plusSeconds(900))));
    }

    @Test
    void failsClosedForExpiryRevocationAndRevocationIndexFailure() throws Exception {
        assertThrows(UserAccessTokenInvalidException.class, () -> verifier((jti, kid) -> false)
                .verifyPlatformToken("Bearer " + token(Map.of(), NOW.minusSeconds(900), NOW.minusSeconds(31))));
        assertThrows(UserAccessTokenInvalidException.class, () -> verifier((jti, kid) -> true)
                .verifyPlatformToken("Bearer " + token(Map.of(), NOW, NOW.plusSeconds(900))));
        assertThrows(UserAccessTokenInvalidException.class, () -> verifier((jti, kid) -> {
            throw new IllegalStateException("redis unavailable");
        }).verifyPlatformToken("Bearer " + token(Map.of(), NOW, NOW.plusSeconds(900))));
    }

    @Test
    void rejectsInvalidVerifierConfiguration() {
        UserAccessTokenRevocationChecker revocations = (jti, kid) -> false;
        ServiceJwtVerificationKeyResolver keys = kid -> Optional.empty();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

        assertThrows(IllegalArgumentException.class, () -> new UserAccessTokenVerifier(
                null, revocations, clock, "issuer", "audience", Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new UserAccessTokenVerifier(
                keys, null, clock, "issuer", "audience", Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new UserAccessTokenVerifier(
                keys, revocations, null, "issuer", "audience", Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new UserAccessTokenVerifier(
                keys, revocations, clock, null, "audience", Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new UserAccessTokenVerifier(
                keys, revocations, clock, " ", "audience", Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new UserAccessTokenVerifier(
                keys, revocations, clock, "issuer", null, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new UserAccessTokenVerifier(
                keys, revocations, clock, "issuer", " ", Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new UserAccessTokenVerifier(
                keys, revocations, clock, "issuer", "audience", null));
        assertThrows(IllegalArgumentException.class, () -> new UserAccessTokenVerifier(
                keys, revocations, clock, "issuer", "audience", Duration.ofSeconds(-1)));
    }

    @Test
    void rejectsMalformedHeadersSignatureAndRegisteredClaims() throws Exception {
        UserAccessTokenVerifier verifier = verifier((jti, kid) -> false);
        assertThrows(UserAccessTokenInvalidException.class, () -> verifier.verifyPlatformToken(null));
        assertThrows(UserAccessTokenInvalidException.class, () -> verifier.verifyPlatformToken("Basic token"));
        assertThrows(UserAccessTokenInvalidException.class, () -> verifier.verifyPlatformToken("Bearer "));

        JWTClaimsSet validClaims = claims("https://iam.test", "saas.forge-api", IDENTITY_ID.toString(),
                JTI.toString(), NOW, NOW.plusSeconds(900));
        assertThrows(UserAccessTokenInvalidException.class, () -> verifier.verifyPlatformToken(
                "Bearer " + sign(new JWSHeader.Builder(JWSAlgorithm.RS512)
                        .type(JOSEObjectType.JWT).keyID(key.getKeyID()).build(), validClaims, key)));
        assertThrows(UserAccessTokenInvalidException.class, () -> verifier.verifyPlatformToken(
                "Bearer " + sign(new JWSHeader.Builder(JWSAlgorithm.RS256)
                        .type(new JOSEObjectType("at+jwt")).keyID(key.getKeyID()).build(), validClaims, key)));
        assertThrows(UserAccessTokenInvalidException.class, () -> verifier.verifyPlatformToken(
                "Bearer " + sign(new JWSHeader.Builder(JWSAlgorithm.RS256)
                        .type(JOSEObjectType.JWT).build(), validClaims, key)));

        RSAKey otherKey = new RSAKeyGenerator(2048).keyID(key.getKeyID()).generate();
        assertThrows(UserAccessTokenInvalidException.class, () -> verifier.verifyPlatformToken(
                "Bearer " + sign(new JWSHeader.Builder(JWSAlgorithm.RS256)
                        .type(JOSEObjectType.JWT).keyID(key.getKeyID()).build(), validClaims, otherKey)));
        assertThrows(UserAccessTokenInvalidException.class, () -> verifier.verifyPlatformToken(
                "Bearer " + signedClaims(claims("other", "saas.forge-api", IDENTITY_ID.toString(),
                        JTI.toString(), NOW, NOW.plusSeconds(900)))));
        assertThrows(UserAccessTokenInvalidException.class, () -> verifier.verifyPlatformToken(
                "Bearer " + signedClaims(claims("https://iam.test", "other", IDENTITY_ID.toString(),
                        JTI.toString(), NOW, NOW.plusSeconds(900)))));
        assertThrows(UserAccessTokenInvalidException.class, () -> verifier.verifyPlatformToken(
                "Bearer " + signedClaims(claims("https://iam.test", "saas.forge-api", IDENTITY_ID.toString(),
                        JTI.toString(), NOW.plusSeconds(31), NOW.plusSeconds(900)))));
        assertThrows(UserAccessTokenInvalidException.class, () -> verifier.verifyPlatformToken(
                "Bearer " + signedClaims(claims("https://iam.test", "saas.forge-api", IDENTITY_ID.toString(),
                        JTI.toString(), NOW, NOW)))) ;
        assertThrows(UserAccessTokenInvalidException.class, () -> verifier.verifyPlatformToken(
                "Bearer " + signedClaims(claims("https://iam.test", "saas.forge-api",
                        UUID.randomUUID().toString(), JTI.toString(), NOW, NOW.plusSeconds(900)))));
        assertThrows(UserAccessTokenInvalidException.class, () -> verifier.verifyPlatformToken(
                "Bearer " + signedClaims(claims("https://iam.test", "saas.forge-api",
                        IDENTITY_ID.toString().toUpperCase(), JTI.toString(), NOW, NOW.plusSeconds(900)))));
    }

    private static UserAccessTokenVerifier verifier(UserAccessTokenRevocationChecker revocations) {
        return new UserAccessTokenVerifier(signatureVerifier(), revocations);
    }

    private static UserAccessTokenSignatureVerifier signatureVerifier() {
        ServiceJwtVerificationKey publicKey = new ServiceJwtVerificationKey(
                key.getKeyID(), key.getModulus().toString(), key.getPublicExponent().toString());
        return new UserAccessTokenSignatureVerifier(
                kid -> key.getKeyID().equals(kid) ? Optional.of(publicKey) : Optional.empty(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                "https://iam.test",
                "saas.forge-api",
                Duration.ofSeconds(30));
    }

    private static String token(Map<String, Object> extraClaims, Instant issuedAt, Instant expiresAt)
            throws Exception {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer("https://iam.test")
                .audience("saas.forge-api")
                .issueTime(Date.from(issuedAt))
                .expirationTime(Date.from(expiresAt))
                .claim("identityId", IDENTITY_ID.toString())
                .jwtID(JTI.toString());
        extraClaims.forEach(claims::claim);
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256)
                        .type(JOSEObjectType.JWT)
                        .keyID(key.getKeyID())
                        .build(),
                claims.build());
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    private static JWTClaimsSet claims(
            String issuer,
            String audience,
            String identityId,
            String jti,
            Instant issuedAt,
            Instant expiresAt) {
        return new JWTClaimsSet.Builder()
                .issuer(issuer)
                .audience(audience)
                .issueTime(Date.from(issuedAt))
                .expirationTime(Date.from(expiresAt))
                .claim("identityId", identityId)
                .jwtID(jti)
                .build();
    }

    private static String signedClaims(JWTClaimsSet claims) throws Exception {
        return sign(new JWSHeader.Builder(JWSAlgorithm.RS256)
                .type(JOSEObjectType.JWT)
                .keyID(key.getKeyID())
                .build(), claims, key);
    }

    private static String sign(JWSHeader header, JWTClaimsSet claims, RSAKey signingKey) throws Exception {
        SignedJWT jwt = new SignedJWT(header, claims);
        jwt.sign(new RSASSASigner(signingKey));
        return jwt.serialize();
    }
}
