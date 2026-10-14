package io.saas.forge.iam.application.authorization;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.saas.forge.iam.application.authentication.RevocationIndex;
import io.saas.forge.iam.application.authentication.RevocationIndexUnavailableException;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class PlatformAdminRevokeServiceTest {
    private static final UUID REQUEST = UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4c90");
    private static final UUID ACTOR = UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4c8f");
    private static final UUID TARGET = UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4c91");
    private static final UUID JTI = UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4c92");
    private static final Instant EXPIRY = Instant.parse("2026-10-14T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-14T11:00:00Z"), ZoneOffset.UTC);

    @Test
    void committedRevokeCompletesOnlyAfterHistoricalPlatformJtiIsIndexed() {
        PlatformAdminRevokeTransaction transaction = Mockito.mock(PlatformAdminRevokeTransaction.class);
        RevocationIndex index = Mockito.mock(RevocationIndex.class);
        when(transaction.prepare(ACTOR, TARGET, REQUEST, null)).thenReturn(false);
        when(transaction.pendingJtis(REQUEST)).thenReturn(List.of(new PlatformAdminRevokeTransaction.Jti(JTI, EXPIRY)));
        when(index.isReady()).thenReturn(true);

        new PlatformAdminRevokeService(transaction, index, CLOCK).revoke(ACTOR, TARGET, REQUEST, null);
        verify(index).revokeJti(Mockito.eq(JTI), Mockito.eq(EXPIRY), Mockito.any());
        verify(transaction).complete(REQUEST);
    }

    @Test
    void redisFailureLeavesDurableWorkForRecovery() {
        PlatformAdminRevokeTransaction transaction = Mockito.mock(PlatformAdminRevokeTransaction.class);
        RevocationIndex index = Mockito.mock(RevocationIndex.class);
        when(transaction.pendingJtis(REQUEST)).thenReturn(List.of(new PlatformAdminRevokeTransaction.Jti(JTI, EXPIRY)));
        Mockito.doThrow(new RevocationIndexUnavailableException()).when(index)
                .revokeJti(Mockito.eq(JTI), Mockito.eq(EXPIRY), Mockito.any());

        assertThrows(RevocationIndexUnavailableException.class,
                () -> new PlatformAdminRevokeService(transaction, index, CLOCK).revoke(ACTOR, TARGET, REQUEST, null));
        verify(transaction, Mockito.never()).complete(REQUEST);
    }
}
