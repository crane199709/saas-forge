package io.saas.forge.iam.infrastructure.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.saas.forge.iam.application.authentication.*;
import io.saas.forge.iam.application.bootstrap.ReservedServiceClient;
import io.saas.forge.iam.application.signing.*;
import io.saas.forge.iam.domain.client.*;
import io.saas.forge.iam.domain.signing.*;
import io.saas.forge.iam.support.StubSigningKeyRepository;
import java.nio.file.*;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class ReservedIamServiceAccessTokenProviderTest {
    private static final UUID CLIENT_ID = UUID.fromString("019535d9-0000-7000-8000-000000000001");
    private static final String SECRET = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
    private static final Instant NOW = Instant.parse("2026-08-24T00:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final OAuthClientRepository clients = mock(OAuthClientRepository.class);
    private final RevocationIndex revocations = mock(RevocationIndex.class);
    @TempDir Path directory;
    private ClientCredentialsTokenService tokens;

    @BeforeEach
    void setUp() {
        var client = OAuthClient.register("iam-service", ReservedServiceClient.IAM.allowedScopes(),
                NOW.minusSeconds(60)).identifiedBy(CLIENT_ID);
        when(clients.findActiveBySecretDigest(ClientSecretDigest.fromPlaintext(SECRET), NOW))
                .thenReturn(Optional.of(client));
        when(revocations.isReady()).thenReturn(true);
        var keys = new StubSigningKeyRepository();
        keys.activeKeys(List.of(SigningKey.restore(UUID.randomUUID(), "active-kid", "kms/key/1",
                "modulus", "AQAB", SigningKeyStatus.ACTIVE, NOW.minusSeconds(600),
                NOW.minusSeconds(300), null, null, null)));
        var signing = new JwtSigningService(new ActiveSigningKeyResolver(keys),
                (reference, algorithm, input) -> new byte[32]);
        tokens = new ClientCredentialsTokenService(clients, new ServiceAccessTokenIssuer(signing,
                new ObjectMapper(), new UuidV7Generator(clock, new SecureRandom()), clock,
                "https://iam.test", Duration.ofMinutes(5)), revocations, clock);
    }

    @Test
    void issuesExactServiceIdentityAndScopeWithoutHttpAndCachesTheToken() throws Exception {
        var provider = provider(CLIENT_ID.toString(), SECRET);
        String token = provider.membershipReadToken();
        var claims = new ObjectMapper().readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
        assertEquals(CLIENT_ID.toString(), claims.path("client_id").asText());
        assertEquals("tenant-access:membership:read", claims.path("scope").asText());
        assertFalse(claims.has("tenant_id"));
        assertEquals(token, provider.membershipReadToken());
    }

    @Test
    void failsClosedForInvalidCredentialsAndUnavailableRevocationState() throws Exception {
        assertThrows(TenantAccessUnavailableException.class,
                () -> provider(CLIENT_ID.toString(), "wrong-secret").membershipReadToken());
        assertThrows(TenantAccessUnavailableException.class,
                () -> provider(UUID.randomUUID().toString(), SECRET).membershipReadToken());
        when(revocations.isReady()).thenReturn(false);
        assertThrows(TenantAccessUnavailableException.class,
                () -> provider(CLIENT_ID.toString(), SECRET).membershipReadToken());
        when(revocations.isReady()).thenReturn(true);
        when(revocations.isClientRevoked(CLIENT_ID)).thenReturn(true);
        assertThrows(TenantAccessUnavailableException.class,
                () -> provider(CLIENT_ID.toString(), SECRET).membershipReadToken());
    }

    @Test
    void rejectsMissingMembershipGrantAndUnreadableCredentials() throws Exception {
        var wrongScope = OAuthClient.register("iam-service", Set.of(OAuthScope.RUNTIME_READ),
                NOW.minusSeconds(60)).identifiedBy(CLIENT_ID);
        when(clients.findActiveBySecretDigest(any(), any())).thenReturn(Optional.of(wrongScope));
        assertThrows(TenantAccessUnavailableException.class,
                () -> provider(CLIENT_ID.toString(), SECRET).membershipReadToken());
        var provider = provider(CLIENT_ID.toString(), SECRET);
        Files.delete(directory.resolve("secret"));
        assertThrows(TenantAccessUnavailableException.class, provider::membershipReadToken);
    }

    private ReservedIamServiceAccessTokenProvider provider(String id, String secret) throws Exception {
        Path idFile = Files.writeString(directory.resolve("id"), id);
        Path secretFile = Files.writeString(directory.resolve("secret"), secret);
        return new ReservedIamServiceAccessTokenProvider(tokens, idFile, secretFile, clock);
    }
}
