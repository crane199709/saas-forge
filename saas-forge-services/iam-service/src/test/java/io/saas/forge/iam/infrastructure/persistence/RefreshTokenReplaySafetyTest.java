package io.saas.forge.iam.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.saas.forge.iam.domain.session.RefreshRotation;
import io.saas.forge.iam.domain.session.RefreshTokenFamilyPurpose;
import io.saas.forge.iam.domain.shared.Sha256Digest;
import io.saas.forge.iam.infrastructure.persistence.mapper.RefreshTokenMapper;
import io.saas.forge.iam.infrastructure.persistence.record.RefreshTokenFamilyRow;
import io.saas.forge.iam.infrastructure.persistence.record.RefreshTokenRow;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RefreshTokenReplaySafetyTest {
    private static final Instant NOW = Instant.parse("2026-10-15T00:00:00Z");
    private static final UUID FAMILY = UUID.fromString("019535d9-0001-7000-8000-000000000001");
    private static final UUID IDENTITY = UUID.fromString("019535d9-0001-7000-8000-000000000002");
    private static final UUID TOKEN = UUID.fromString("019535d9-0001-7000-8000-000000000003");
    private static final UUID SUCCESSOR = UUID.fromString("019535d9-0001-7000-8000-000000000004");
    private static final UUID JTI = UUID.fromString("019535d9-0001-7000-8000-000000000005");
    private static final Sha256Digest PRESENTED = digest((byte) 1);
    private static final Sha256Digest NEXT = digest((byte) 2);
    private static final Sha256Digest KEY = digest((byte) 3);

    private final RefreshTokenMapper mapper = mock(RefreshTokenMapper.class);
    private final MyBatisRefreshTokenFamilyRepository repository = new MyBatisRefreshTokenFamilyRepository(mapper);

    @Test
    void staleContextCannotConsumeOrRecoverPresentedRefreshToken() {
        givenPresentedToken(false, RefreshTokenFamilyPurpose.USER_PLATFORM, 2);
        var result = rotate(KEY, 1);
        assertEquals(RefreshRotation.Status.CONTEXT_CHANGED, result.status());
        verify(mapper, never()).markTokenConsumed(any(), any());
        verify(mapper, never()).insertToken(any());
    }

    @Test
    void retryWithSameKeyWithinWindowRecoversOnceAndInvalidatesSuccessor() {
        RefreshTokenRow token = givenPresentedToken(true, RefreshTokenFamilyPurpose.USER_PLATFORM, 0);
        token.setRotationKeyDigest(KEY.value());
        token.setRecoveryExpiresAt(time(NOW.plusSeconds(60)));
        token.setSuccessorTokenId(SUCCESSOR);
        token.setSuccessorAccessJti(JTI);
        RefreshTokenRow successor = new RefreshTokenRow();
        successor.setId(SUCCESSOR);
        successor.setIssuedAt(time(NOW.minusSeconds(5)));
        when(mapper.lockTokenById(SUCCESSOR)).thenReturn(successor);
        when(mapper.markTokenConsumed(eq(SUCCESSOR), any())).thenReturn(1);
        when(mapper.markRecovered(eq(TOKEN), any())).thenReturn(1);
        var result = rotate(KEY, 0);
        assertEquals(RefreshRotation.Status.RECOVERED, result.status());
        assertEquals(JTI, result.replacedAccessJti());
        verify(mapper).markTokenConsumed(eq(SUCCESSOR), any());
        verify(mapper).insertToken(any());
        verify(mapper).markRecovered(eq(TOKEN), any());
    }

    @Test
    void changedKeyOrExpiredRecoveryWindowRevokesTheWholeFamily() {
        RefreshTokenRow token = givenPresentedToken(true, RefreshTokenFamilyPurpose.USER_PLATFORM, 0);
        token.setRotationKeyDigest(KEY.value());
        token.setRecoveryExpiresAt(time(NOW.plusSeconds(60)));
        assertEquals(RefreshRotation.Status.REPLAYED, rotate(digest((byte) 9), 0).status());
        token.setRecoveryExpiresAt(time(NOW.minusSeconds(1)));
        assertEquals(RefreshRotation.Status.REPLAYED, rotate(KEY, 0).status());
        verify(mapper, never()).lockTokenById(any());
        verify(mapper, never()).insertToken(any());
    }

    @Test
    void restrictedPasswordChangeSessionCannotRotateIntoNormalBrowserSession() {
        givenPresentedToken(false, RefreshTokenFamilyPurpose.INITIAL_PASSWORD_CHANGE, 0);
        assertEquals(RefreshRotation.Status.PURPOSE_MISMATCH, rotate(KEY, 0).status());
        verify(mapper, never()).markTokenConsumed(any(), any());
        verify(mapper, never()).insertToken(any());
    }

    private RefreshRotation rotate(Sha256Digest key, long version) {
        return repository.rotateForRefresh(PRESENTED, NEXT, key, version, null, null, JTI,
                Duration.ofMinutes(1), NOW);
    }

    private RefreshTokenRow givenPresentedToken(boolean consumed, RefreshTokenFamilyPurpose purpose, long version) {
        RefreshTokenRow token = new RefreshTokenRow();
        token.setId(TOKEN);
        token.setFamilyId(FAMILY);
        token.setIssuedAt(time(NOW.minusSeconds(60)));
        if (consumed) token.setConsumedAt(time(NOW.minusSeconds(30)));
        when(mapper.lockTokenByDigest(any(byte[].class))).thenReturn(token);
        RefreshTokenFamilyRow family = new RefreshTokenFamilyRow();
        family.setId(FAMILY);
        family.setIdentityId(IDENTITY);
        family.setFamilyPurpose(purpose.name());
        family.setInitialCredentialId(purpose == RefreshTokenFamilyPurpose.INITIAL_PASSWORD_CHANGE
                ? UUID.fromString("019535d9-0001-7000-8000-000000000006") : null);
        family.setContextVersion(version);
        family.setLastUsedAt(time(NOW.minusSeconds(30)));
        family.setAbsoluteExpiresAt(time(NOW.plusSeconds(300)));
        when(mapper.lockFamilyById(FAMILY)).thenReturn(family);
        return token;
    }

    private static Sha256Digest digest(byte value) {
        byte[] bytes = new byte[32];
        bytes[0] = value;
        return Sha256Digest.of(bytes);
    }

    private static OffsetDateTime time(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
