package io.saas.forge.iam.application.authentication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.saas.forge.iam.domain.session.RevocationFence;
import io.saas.forge.iam.domain.session.RevocationFenceRepository;
import io.saas.forge.iam.domain.session.RevocationFenceTarget;
import io.saas.forge.iam.domain.session.UserSessionFenceRelease;
import io.saas.forge.iam.domain.session.UserSessionRevocationBatch;
import io.saas.forge.iam.domain.session.UserSessionRevocationRepository;
import io.saas.forge.iam.domain.session.UserSessionRevocationStatus;
import io.saas.forge.iam.domain.session.UserSessionRevocationWorkflow;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UserSessionRevocationServiceRecoveryTest {
    private static final UUID REQUEST = UUID.fromString("019535d9-0001-7000-8000-000000000001");
    private static final UUID RELEASE = UUID.fromString("019535d9-0001-7000-8000-000000000002");
    private static final UUID IDENTITY = UUID.fromString("019535d9-0001-7000-8000-000000000003");
    private static final UUID OTHER_IDENTITY = UUID.fromString("019535d9-0001-7000-8000-000000000004");
    private static final Instant NOW = Instant.parse("2026-10-15T00:00:00Z");
    private static final RevocationFenceTarget TARGET = RevocationFenceTarget.identity(IDENTITY);
    private static final RevocationFenceTarget OTHER_TARGET = RevocationFenceTarget.identity(OTHER_IDENTITY);

    private final RevocationFenceRepository fences = mock(RevocationFenceRepository.class);
    private final UserSessionRevocationRepository workflows = mock(UserSessionRevocationRepository.class);
    private final RevocationIndex index = mock(RevocationIndex.class);
    private final UserSessionRevocationTransaction transaction = mock(UserSessionRevocationTransaction.class);
    private final UserSessionRevocationRecoveryPolicy policy =
            new UserSessionRevocationRecoveryPolicy(25, Duration.ofSeconds(30), Duration.ofSeconds(5), 3);
    private final UserSessionRevocationService service = new UserSessionRevocationService(
            fences, workflows, index, transaction, policy, "worker-1", Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void idempotentCompletedRequestReturnsDurableCountsAndRejectsTargetRebinding() {
        when(workflows.find(REQUEST)).thenReturn(Optional.of(workflow(UserSessionRevocationStatus.COMPLETED, 1)));
        var result = service.revoke(REQUEST, TARGET);
        assertEquals(UserSessionRevocationResult.Status.COMPLETED, result.status());
        assertEquals(2, result.revokedFamilyCount());
        assertEquals(3, result.revokedJtiCount());
        verify(workflows, never()).claim(any(), any(), any(), any(), any(Integer.class));
        assertThrows(RevocationFenceConflictException.class, () -> service.revoke(REQUEST, OTHER_TARGET));
        assertThrows(IllegalArgumentException.class, () -> service.revoke(UUID.randomUUID(), TARGET));
        assertThrows(IllegalArgumentException.class, () -> service.revoke(REQUEST, null));
    }

    @Test
    void pendingLeaseReturnsRetryWithoutOpeningAnotherFence() {
        var pending = workflow(UserSessionRevocationStatus.PENDING, 1);
        when(workflows.find(REQUEST)).thenReturn(Optional.of(pending));
        when(workflows.claim(eq(REQUEST), any(), any(), any(), eq(3))).thenReturn(Optional.empty());
        var result = service.revoke(REQUEST, TARGET);
        assertEquals(UserSessionRevocationResult.Status.PENDING, result.status());
        assertEquals(12, result.retryAfterSeconds());
        verify(transaction, never()).prepare(any(), any(), any());
    }

    @Test
    void redisFailureKeepsRequestRecoverableAndExhaustsOnlyAfterAttemptLimit() {
        var pending = workflow(UserSessionRevocationStatus.PENDING, 1);
        when(workflows.find(REQUEST)).thenReturn(Optional.of(pending));
        when(workflows.claim(eq(REQUEST), any(), any(), any(), eq(3))).thenReturn(Optional.of(pending));
        when(workflows.loadBatch(eq(pending), eq(25), eq(NOW)))
                .thenThrow(new RevocationIndexUnavailableException());
        assertThrows(RevocationIndexUnavailableException.class, () -> service.revoke(REQUEST, TARGET));
        verify(workflows).scheduleRetry(pending, NOW.plusSeconds(5), RevocationIndexUnavailableException.CODE);

        var exhausted = workflow(UserSessionRevocationStatus.PENDING, 3);
        when(workflows.claimNext(any(), any(), any(), eq(3))).thenReturn(Optional.of(exhausted));
        when(workflows.loadBatch(eq(exhausted), eq(25), eq(NOW)))
                .thenThrow(new IllegalStateException("database unavailable"));
        service.recoverNext();
        verify(workflows).exhaust(exhausted, NOW, "INTERNAL_RECOVERY_FAILURE");
    }

    @Test
    void explicitRecoveryRequiresSameActiveFenceAndRecoverableState() {
        var recoveryRequired = workflow(UserSessionRevocationStatus.RECOVERY_REQUIRED, 3);
        when(workflows.find(REQUEST)).thenReturn(Optional.of(recoveryRequired));
        assertThrows(UserSessionRevocationRecoveryRequiredException.class, () -> service.revoke(REQUEST, TARGET));
        assertThrows(RevocationFenceConflictException.class, () -> service.recover(REQUEST, OTHER_TARGET));
        assertThrows(RevocationFenceConflictException.class, () -> service.recover(REQUEST));
        when(fences.findByRequestId(REQUEST)).thenReturn(Optional.of(RevocationFence.establish(REQUEST, TARGET, NOW)));
        service.recover(REQUEST, TARGET);
        verify(workflows).recover(REQUEST, NOW);

        when(workflows.find(REQUEST)).thenReturn(Optional.of(workflow(UserSessionRevocationStatus.PENDING, 1)));
        service.recover(REQUEST);
        when(workflows.find(REQUEST)).thenReturn(Optional.of(workflow(UserSessionRevocationStatus.COMPLETED, 1)));
        assertThrows(IllegalStateException.class, () -> service.recover(REQUEST));
    }

    @Test
    void fenceReleaseIsIdempotentButCannotClearAnotherGeneration() {
        when(workflows.findRelease(RELEASE)).thenReturn(Optional.of(new UserSessionFenceRelease(RELEASE, REQUEST, TARGET)));
        service.release(RELEASE, REQUEST, TARGET);
        assertThrows(RevocationFenceConflictException.class, () -> service.release(RELEASE, REQUEST, OTHER_TARGET));
        when(workflows.findRelease(RELEASE)).thenReturn(Optional.empty());
        when(workflows.find(REQUEST)).thenReturn(Optional.of(workflow(UserSessionRevocationStatus.COMPLETED, 1)));
        when(fences.findByRequestId(REQUEST)).thenReturn(Optional.of(RevocationFence.establish(REQUEST, TARGET, NOW)));
        when(index.releaseFence(any())).thenReturn(false, true);
        assertThrows(RevocationFenceConflictException.class, () -> service.release(RELEASE, REQUEST, TARGET));
        when(fences.release(eq(REQUEST), eq(NOW))).thenReturn(true);
        service.release(RELEASE, REQUEST, TARGET);
        verify(workflows).recordRelease(RELEASE, REQUEST, TARGET, NOW);
    }

    private static UserSessionRevocationWorkflow workflow(UserSessionRevocationStatus status, int attempt) {
        return new UserSessionRevocationWorkflow(REQUEST, TARGET, status, null, 2, 3, attempt,
                NOW.plusSeconds(12), null, null, 0,
                status == UserSessionRevocationStatus.RECOVERY_REQUIRED ? NOW : null,
                null, status == UserSessionRevocationStatus.COMPLETED ? NOW : null);
    }
}
