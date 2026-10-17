package io.saas.forge.iam.application.authentication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.saas.forge.iam.domain.identity.IdentityRepository;
import io.saas.forge.iam.domain.outbox.OutboxEventRepository;
import io.saas.forge.iam.domain.session.RevocationFenceRepository;
import io.saas.forge.iam.domain.session.RevocationFenceTarget;
import io.saas.forge.iam.domain.session.UserSessionRevocationRepository;
import io.saas.forge.iam.domain.session.UserSessionRevocationStatus;
import io.saas.forge.iam.domain.session.UserSessionRevocationWorkflow;
import io.saas.forge.iam.infrastructure.persistence.PasswordRecoveryRepository;
import io.saas.forge.iam.infrastructure.persistence.PasswordRecoveryRepository.Challenge;
import io.saas.forge.iam.infrastructure.security.RedisPasswordRecoveryRateLimiter;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class PasswordRecoveryServiceSafetyTest {
    private static final UUID REQUEST = UUID.fromString("019535d9-0001-7000-8000-000000000091");
    private static final UUID IDENTITY = UUID.fromString("019535d9-0001-7000-8000-000000000092");
    private static final UUID CREDENTIAL = UUID.fromString("019535d9-0001-7000-8000-000000000093");
    private static final Instant NOW = Instant.parse("2026-10-14T08:00:00Z");
    private final PasswordRecoveryRepository challenges = mock(PasswordRecoveryRepository.class);
    private final UserSessionRevocationRepository workflows = mock(UserSessionRevocationRepository.class);
    private final UserSessionRevocationService revocations = mock(UserSessionRevocationService.class);
    private final OutboxEventRepository outbox = mock(OutboxEventRepository.class);
    private final PasswordRecoveredEventFactory events = mock(PasswordRecoveredEventFactory.class);
    private final TransactionTemplate transactions = mock(TransactionTemplate.class);
    private final PasswordRecoveryService service = new PasswordRecoveryService(
            challenges, mock(IdentityRepository.class), mock(RevocationFenceRepository.class),
            mock(PasswordSetupChallengeIssuer.class), mock(PasswordRecoveryMailer.class),
            mock(RedisPasswordRecoveryRateLimiter.class), mock(PasswordPolicy.class),
            mock(CompromisedPasswordChecker.class), mock(PasswordVerifier.class),
            mock(UserSessionRevocationTransaction.class), revocations, workflows, outbox, events,
            transactions, Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void runTransactionCallbacks() {
        doAnswer(invocation -> {
            Consumer<TransactionStatus> action = invocation.getArgument(0);
            action.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactions).executeWithoutResult(any());
    }

    @Test
    void unfinishedOrAlreadyCompletedChallengeNeverReportsRecoverySuccess() {
        when(challenges.lockById(REQUEST)).thenReturn(Optional.empty());
        assertThrows(PasswordRecoveryTokenInvalidException.class, () -> service.continueRecovery(REQUEST));
        when(challenges.lockById(REQUEST)).thenReturn(Optional.of(challenge(null, null)));
        assertThrows(PasswordRecoveryTokenInvalidException.class, () -> service.continueRecovery(REQUEST));
        when(challenges.lockById(REQUEST)).thenReturn(Optional.of(challenge(NOW, NOW)));
        assertThrows(PasswordRecoveryTokenInvalidException.class, () -> service.continueRecovery(REQUEST));
        verify(revocations, never()).revoke(any(), any());
        verify(outbox, never()).append(any());
    }

    @Test
    void revocationRecoveryFailureOrPendingBatchKeepsPasswordRecoveryPending() {
        when(challenges.lockById(REQUEST)).thenReturn(Optional.of(challenge(NOW, null)));
        when(workflows.find(REQUEST)).thenReturn(Optional.of(workflow(UserSessionRevocationStatus.RECOVERY_REQUIRED)));
        doThrow(new IllegalStateException("retry later")).when(revocations)
                .recover(eq(REQUEST), eq(RevocationFenceTarget.identity(IDENTITY)));
        assertEquals(PasswordRecoveryService.Status.PENDING, service.continueRecovery(REQUEST));
        verify(outbox, never()).append(any());

        org.mockito.Mockito.reset(revocations);
        when(workflows.find(REQUEST)).thenReturn(Optional.of(workflow(UserSessionRevocationStatus.PENDING)));
        when(revocations.revoke(REQUEST, RevocationFenceTarget.identity(IDENTITY)))
                .thenReturn(UserSessionRevocationResult.pending(5));
        assertEquals(PasswordRecoveryService.Status.PENDING, service.continueRecovery(REQUEST));
        verify(outbox, never()).append(any());
    }

    @Test
    void completedRevocationPublishesOnlyAfterFenceReleaseAndDurableCompletion() {
        when(challenges.lockById(REQUEST)).thenReturn(Optional.of(challenge(NOW, null)));
        when(workflows.find(REQUEST)).thenReturn(Optional.of(workflow(UserSessionRevocationStatus.COMPLETED)));
        when(revocations.revoke(REQUEST, RevocationFenceTarget.identity(IDENTITY)))
                .thenReturn(UserSessionRevocationResult.completed(1, 2));
        when(challenges.complete(REQUEST, NOW)).thenReturn(true);
        assertEquals(PasswordRecoveryService.Status.COMPLETED, service.continueRecovery(REQUEST));
        verify(revocations).release(REQUEST, REQUEST, RevocationFenceTarget.identity(IDENTITY));
        verify(challenges).complete(REQUEST, NOW);
        verify(outbox).append(any());
    }

    @Test
    void finishFailureNeverReportsCompletionAndWorkerSkipsUnfinishedWorkflows() {
        when(challenges.lockById(REQUEST)).thenReturn(Optional.of(challenge(NOW, null)));
        when(workflows.find(REQUEST)).thenReturn(Optional.of(workflow(UserSessionRevocationStatus.COMPLETED)));
        when(revocations.revoke(REQUEST, RevocationFenceTarget.identity(IDENTITY)))
                .thenReturn(UserSessionRevocationResult.completed(1, 2));
        doThrow(new IllegalStateException("fence unavailable")).when(revocations)
                .release(REQUEST, REQUEST, RevocationFenceTarget.identity(IDENTITY));
        assertEquals(PasswordRecoveryService.Status.PENDING, service.continueRecovery(REQUEST));
        verify(outbox, never()).append(any());

        org.mockito.Mockito.reset(revocations);
        when(challenges.unfinished()).thenReturn(List.of(challenge(NOW, null)));
        when(workflows.find(REQUEST)).thenReturn(Optional.of(workflow(UserSessionRevocationStatus.PENDING)));
        service.finishCompleted();
        verify(revocations, never()).release(any(), any(), any());
    }

    private Challenge challenge(Instant started, Instant completed) {
        return new Challenge(REQUEST, IDENTITY, NOW.minusSeconds(60), NOW.plusSeconds(1800),
                null, started, completed, CREDENTIAL);
    }

    private UserSessionRevocationWorkflow workflow(UserSessionRevocationStatus status) {
        return new UserSessionRevocationWorkflow(REQUEST, RevocationFenceTarget.identity(IDENTITY), status,
                null, 0, 0, 0, NOW, null, null, 0,
                status == UserSessionRevocationStatus.RECOVERY_REQUIRED ? NOW : null,
                null, status == UserSessionRevocationStatus.COMPLETED ? NOW : null);
    }
}
