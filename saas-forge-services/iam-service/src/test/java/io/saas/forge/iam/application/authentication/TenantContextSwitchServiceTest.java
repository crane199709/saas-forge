package io.saas.forge.iam.application.authentication;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.saas.forge.iam.domain.session.RefreshTokenFamily;
import io.saas.forge.iam.domain.session.RefreshTokenFamilyPurpose;
import io.saas.forge.iam.domain.session.RefreshTokenFamilyRepository;
import io.saas.forge.iam.domain.session.TenantContextSwitchClaim;
import io.saas.forge.iam.domain.session.TenantContextSwitchRepository;
import io.saas.forge.iam.domain.session.TenantContextSwitchStatus;
import io.saas.forge.iam.domain.session.TenantContextSwitchWorkflow;
import io.saas.forge.iam.domain.shared.Sha256Digest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TenantContextSwitchServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-24T00:00:00Z");
    private static final String REFRESH_TOKEN = "A".repeat(43);
    private static final UUID FAMILY_ID = uuidV7(1);
    private static final UUID IDENTITY_ID = uuidV7(2);
    private static final UUID CURRENT_MEMBERSHIP_ID = uuidV7(3);
    private static final UUID CURRENT_TENANT_ID = uuidV7(4);
    private static final UUID TARGET_MEMBERSHIP_ID = uuidV7(5);
    private static final UUID TARGET_TENANT_ID = uuidV7(6);
    private static final UUID IDEMPOTENCY_KEY = uuidV7(7);
    private static final UUID WORKFLOW_ID = uuidV7(8);

    private RefreshTokenFamilyRepository families;
    private TenantContextSwitchRepository workflows;
    private MembershipValidation memberships;
    private TenantContextSwitchTransaction transaction;
    private TenantContextSwitchService service;
    private RefreshTokenFamily family;

    @BeforeEach
    void setUp() {
        families = mock(RefreshTokenFamilyRepository.class);
        workflows = mock(TenantContextSwitchRepository.class);
        memberships = mock(MembershipValidation.class);
        transaction = mock(TenantContextSwitchTransaction.class);
        service = new TenantContextSwitchService(
                families, workflows, memberships, new RefreshTokenIssuer(new SecureRandom()), transaction,
                new TenantContextSwitchRecoveryPolicy(
                        Duration.ofSeconds(30), Duration.ofSeconds(1), Duration.ofMinutes(1), 10),
                "test-worker", Clock.fixed(NOW, ZoneOffset.UTC));
        family = RefreshTokenFamily.start(
                        IDENTITY_ID, RefreshTokenFamilyPurpose.USER_TENANT,
                        CURRENT_MEMBERSHIP_ID, CURRENT_TENANT_ID, NOW.minusSeconds(10))
                .identifiedBy(FAMILY_ID);
        when(families.findUsableByTokenDigest(any(), eq(NOW))).thenReturn(Optional.of(family));
        when(families.findById(FAMILY_ID)).thenReturn(Optional.of(family));
    }

    @Test
    void persistsBeforeSequentialValidationAndCompletesStableNoOp() {
        TenantContextSwitchWorkflow workflow = workflow(CURRENT_MEMBERSHIP_ID, TenantContextSwitchStatus.PENDING);
        claimed(TenantContextSwitchClaim.Status.CREATED, workflow);
        when(memberships.validate(IDENTITY_ID, CURRENT_MEMBERSHIP_ID))
                .thenReturn(Optional.of(new ValidatedMembership(CURRENT_MEMBERSHIP_ID, CURRENT_TENANT_ID)));

        assertDoesNotThrow(() -> service.switchContext(
                IDEMPOTENCY_KEY, REFRESH_TOKEN, CURRENT_MEMBERSHIP_ID));

        verify(transaction).complete(workflow, TenantContextSwitchStatus.NO_OP, NOW);
    }

    @Test
    void currentDenialTerminatesFamilyAndDoesNotInspectTarget() {
        TenantContextSwitchWorkflow workflow = workflow(TARGET_MEMBERSHIP_ID, TenantContextSwitchStatus.PENDING);
        claimed(TenantContextSwitchClaim.Status.CREATED, workflow);
        when(memberships.validate(IDENTITY_ID, CURRENT_MEMBERSHIP_ID)).thenReturn(Optional.empty());

        assertThrows(TenantContextSwitchAccessRejectedException.class,
                () -> service.switchContext(IDEMPOTENCY_KEY, REFRESH_TOKEN, TARGET_MEMBERSHIP_ID));

        verify(transaction).rejectCurrent(workflow, NOW);
        verify(memberships, never()).validate(IDENTITY_ID, TARGET_MEMBERSHIP_ID);
    }

    @Test
    void targetDenialPreservesCurrentSessionAndUsesReasonFreeFailure() {
        TenantContextSwitchWorkflow workflow = workflow(TARGET_MEMBERSHIP_ID, TenantContextSwitchStatus.PENDING);
        claimed(TenantContextSwitchClaim.Status.CREATED, workflow);
        when(memberships.validate(IDENTITY_ID, CURRENT_MEMBERSHIP_ID))
                .thenReturn(Optional.of(new ValidatedMembership(CURRENT_MEMBERSHIP_ID, CURRENT_TENANT_ID)));
        when(memberships.validate(IDENTITY_ID, TARGET_MEMBERSHIP_ID)).thenReturn(Optional.empty());

        assertThrows(TenantContextSwitchAccessRejectedException.class,
                () -> service.switchContext(IDEMPOTENCY_KEY, REFRESH_TOKEN, TARGET_MEMBERSHIP_ID));

        verify(transaction).complete(workflow, TenantContextSwitchStatus.TARGET_REJECTED, NOW);
        verify(transaction, never()).rejectCurrent(any(), any());
    }

    @Test
    void validatedRealChangeCommitsSwitchAndReturnsStableSuccess() {
        TenantContextSwitchWorkflow workflow = workflow(TARGET_MEMBERSHIP_ID, TenantContextSwitchStatus.PENDING);
        claimed(TenantContextSwitchClaim.Status.CREATED, workflow);
        validMemberships();

        assertDoesNotThrow(() -> service.switchContext(
                IDEMPOTENCY_KEY, REFRESH_TOKEN, TARGET_MEMBERSHIP_ID));

        verify(transaction).switchContext(
                workflow, family, 0, TARGET_MEMBERSHIP_ID, TARGET_TENANT_ID, NOW, null);
    }

    @Test
    void tenantAccessFailureSchedulesDurableRetryAndReturnsPending() {
        TenantContextSwitchWorkflow workflow = workflow(TARGET_MEMBERSHIP_ID, TenantContextSwitchStatus.PENDING);
        claimed(TenantContextSwitchClaim.Status.CREATED, workflow);
        when(memberships.validate(IDENTITY_ID, CURRENT_MEMBERSHIP_ID))
                .thenThrow(new TenantAccessUnavailableException(new IllegalStateException("outage")));

        TenantContextSwitchPendingException pending = assertThrows(TenantContextSwitchPendingException.class,
                () -> service.switchContext(IDEMPOTENCY_KEY, REFRESH_TOKEN, TARGET_MEMBERSHIP_ID));

        assertEquals(1, pending.retryAfterSeconds());
        verify(workflows).scheduleRetry(workflow, NOW.plusSeconds(1), TenantAccessUnavailableException.CODE);
        verify(transaction, never()).complete(any(), any(), any());
    }

    @Test
    void pendingReplayDoesNotBypassLease() {
        TenantContextSwitchWorkflow workflow = workflow(TARGET_MEMBERSHIP_ID, TenantContextSwitchStatus.PENDING);
        claimed(TenantContextSwitchClaim.Status.REPLAY, workflow);

        assertThrows(TenantContextSwitchPendingException.class,
                () -> service.switchContext(IDEMPOTENCY_KEY, REFRESH_TOKEN, TARGET_MEMBERSHIP_ID));

        verify(memberships, never()).validate(any(), any());
    }

    @Test
    void exhaustedOriginalKeyRequiresNewKeyWithoutCallingTenantAccess() {
        TenantContextSwitchWorkflow workflow = exhaustedWorkflow();
        claimed(TenantContextSwitchClaim.Status.RECOVERY_EXHAUSTED, workflow);

        TenantContextSwitchConflictException conflict = assertThrows(
                TenantContextSwitchConflictException.class,
                () -> service.switchContext(IDEMPOTENCY_KEY, REFRESH_TOKEN, TARGET_MEMBERSHIP_ID));

        assertEquals(TenantContextSwitchConflictException.RETRY_REQUIRED_CODE, conflict.code());
        verify(memberships, never()).validate(any(), any());
    }

    @Test
    void workerRecoversClaimedWorkflowAfterRestart() {
        TenantContextSwitchWorkflow workflow = workflow(TARGET_MEMBERSHIP_ID, TenantContextSwitchStatus.PENDING);
        when(workflows.claimNext("test-worker", NOW, NOW.plusSeconds(30), 10))
                .thenReturn(Optional.of(workflow));
        validMemberships();

        service.recoverNext();

        verify(transaction).switchContext(
                workflow, family, 0, TARGET_MEMBERSHIP_ID, TARGET_TENANT_ID, NOW, null);
    }

    @Test
    void workerRejectsCurrentMembershipLostDuringRecoveryWithoutCheckingTarget() {
        TenantContextSwitchWorkflow workflow = workflow(TARGET_MEMBERSHIP_ID, TenantContextSwitchStatus.PENDING);
        when(workflows.claimNext("test-worker", NOW, NOW.plusSeconds(30), 10))
                .thenReturn(Optional.of(workflow));
        when(memberships.validate(IDENTITY_ID, CURRENT_MEMBERSHIP_ID)).thenReturn(Optional.empty());

        service.recoverNext();

        verify(transaction).rejectCurrent(workflow, NOW);
        verify(memberships, never()).validate(IDENTITY_ID, TARGET_MEMBERSHIP_ID);
        verify(workflows, never()).scheduleRetry(any(), any(), any());
    }

    @Test
    void workerRejectsTargetMembershipLostDuringRecoveryWithoutEndingCurrentSession() {
        TenantContextSwitchWorkflow workflow = workflow(TARGET_MEMBERSHIP_ID, TenantContextSwitchStatus.PENDING);
        when(workflows.claimNext("test-worker", NOW, NOW.plusSeconds(30), 10))
                .thenReturn(Optional.of(workflow));
        when(memberships.validate(IDENTITY_ID, CURRENT_MEMBERSHIP_ID))
                .thenReturn(Optional.of(new ValidatedMembership(CURRENT_MEMBERSHIP_ID, CURRENT_TENANT_ID)));
        when(memberships.validate(IDENTITY_ID, TARGET_MEMBERSHIP_ID)).thenReturn(Optional.empty());

        service.recoverNext();

        verify(transaction).complete(workflow, TenantContextSwitchStatus.TARGET_REJECTED, NOW);
        verify(transaction, never()).rejectCurrent(any(), any());
        verify(workflows, never()).scheduleRetry(any(), any(), any());
    }

    @Test
    void targetLostBetweenValidationAndCommitRejectsSwitchWithoutEndingCurrentSession() {
        TenantContextSwitchWorkflow workflow = workflow(TARGET_MEMBERSHIP_ID, TenantContextSwitchStatus.PENDING);
        claimed(TenantContextSwitchClaim.Status.CREATED, workflow);
        validMemberships();
        doThrow(new AccessContextUnavailableException()).when(transaction).switchContext(
                workflow, family, 0, TARGET_MEMBERSHIP_ID, TARGET_TENANT_ID, NOW, null);

        TenantContextSwitchAccessRejectedException rejection = assertThrows(
                TenantContextSwitchAccessRejectedException.class,
                () -> service.switchContext(IDEMPOTENCY_KEY, REFRESH_TOKEN, TARGET_MEMBERSHIP_ID));

        assertEquals(false, rejection.clearRefreshCookie());
        verify(transaction).complete(workflow, TenantContextSwitchStatus.TARGET_REJECTED, NOW);
        verify(transaction, never()).rejectCurrent(any(), any());
    }

    @Test
    void workerStopsAtMaximumAndStoresOnlyControlledFailureSummary() {
        TenantContextSwitchWorkflow workflow = workflow(
                TARGET_MEMBERSHIP_ID, TenantContextSwitchStatus.PENDING, 10);
        when(workflows.claimNext("test-worker", NOW, NOW.plusSeconds(30), 10))
                .thenReturn(Optional.of(workflow));
        when(memberships.validate(IDENTITY_ID, CURRENT_MEMBERSHIP_ID))
                .thenThrow(new IllegalStateException("token=secret@example.test redis:key"));

        service.recoverNext();

        verify(workflows).exhaustRecovery(workflow, NOW, "INTERNAL_RECOVERY_FAILURE");
        verify(workflows, never()).scheduleRetry(any(), any(), any());
    }

    @Test
    void wrongPurposeAndInvalidCookieFailBeforeTenantAccess() {
        RefreshTokenFamily platformFamily = RefreshTokenFamily.start(
                        IDENTITY_ID, RefreshTokenFamilyPurpose.USER_PLATFORM, null, null, NOW.minusSeconds(10))
                .identifiedBy(FAMILY_ID);
        when(families.findUsableByTokenDigest(any(), eq(NOW))).thenReturn(Optional.of(platformFamily));

        assertThrows(TenantContextSwitchSessionInvalidException.class,
                () -> service.switchContext(IDEMPOTENCY_KEY, REFRESH_TOKEN, TARGET_MEMBERSHIP_ID));
        assertThrows(TenantContextSwitchSessionInvalidException.class,
                () -> service.switchContext(IDEMPOTENCY_KEY, "invalid", TARGET_MEMBERSHIP_ID));
        verify(workflows, never()).claim(
                any(), any(Long.class), any(), any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    void idempotencyAndFamilyConflictsDoNotCallTenantAccess() {
        TenantContextSwitchWorkflow workflow = workflow(TARGET_MEMBERSHIP_ID, TenantContextSwitchStatus.PENDING);
        claimed(TenantContextSwitchClaim.Status.TARGET_CONFLICT, workflow);
        assertThrows(TenantContextSwitchConflictException.class,
                () -> service.switchContext(IDEMPOTENCY_KEY, REFRESH_TOKEN, TARGET_MEMBERSHIP_ID));

        claimed(TenantContextSwitchClaim.Status.FAMILY_IN_PROGRESS, workflow);
        assertThrows(TenantContextSwitchConflictException.class,
                () -> service.switchContext(IDEMPOTENCY_KEY, REFRESH_TOKEN, TARGET_MEMBERSHIP_ID));
        verify(memberships, never()).validate(any(), any());
    }

    private void claimed(TenantContextSwitchClaim.Status status, TenantContextSwitchWorkflow workflow) {
        when(workflows.claim(
                any(), any(Long.class), any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(new TenantContextSwitchClaim(status, workflow));
    }

    @Test
    void recoveryPolicyUsesExponentialBackoffWithConfiguredCap() {
        TenantContextSwitchRecoveryPolicy policy = new TenantContextSwitchRecoveryPolicy(
                Duration.ofSeconds(30), Duration.ofSeconds(1), Duration.ofSeconds(8), 10);

        assertEquals(Duration.ofSeconds(1), policy.retryDelay(1));
        assertEquals(Duration.ofSeconds(2), policy.retryDelay(2));
        assertEquals(Duration.ofSeconds(8), policy.retryDelay(4));
        assertEquals(Duration.ofSeconds(8), policy.retryDelay(10));
        assertEquals(10, policy.maximumAttempts());
    }

    private void validMemberships() {
        when(memberships.validate(IDENTITY_ID, CURRENT_MEMBERSHIP_ID))
                .thenReturn(Optional.of(new ValidatedMembership(CURRENT_MEMBERSHIP_ID, CURRENT_TENANT_ID)));
        when(memberships.validate(IDENTITY_ID, TARGET_MEMBERSHIP_ID))
                .thenReturn(Optional.of(new ValidatedMembership(TARGET_MEMBERSHIP_ID, TARGET_TENANT_ID)));
    }

    private static TenantContextSwitchWorkflow workflow(
            UUID targetMembershipId, TenantContextSwitchStatus status) {
        return workflow(targetMembershipId, status, 1);
    }

    private static TenantContextSwitchWorkflow workflow(
            UUID targetMembershipId, TenantContextSwitchStatus status, int attemptCount) {
        Instant completedAt = status == TenantContextSwitchStatus.PENDING ? null : NOW;
        return new TenantContextSwitchWorkflow(
                WORKFLOW_ID, FAMILY_ID, IDEMPOTENCY_KEY, targetMembershipId,
                Sha256Digest.of(new byte[32]), 0, status,
                status == TenantContextSwitchStatus.NO_OP ? 204 : null,
                NOW, completedAt, null, attemptCount, NOW, "test-worker", NOW.plusSeconds(30), null, null);
    }

    private static TenantContextSwitchWorkflow exhaustedWorkflow() {
        return new TenantContextSwitchWorkflow(
                WORKFLOW_ID, FAMILY_ID, IDEMPOTENCY_KEY, TARGET_MEMBERSHIP_ID,
                Sha256Digest.of(new byte[32]), 0, TenantContextSwitchStatus.PENDING,
                null, NOW, null, null, 10, NOW, null, null, NOW, TenantAccessUnavailableException.CODE);
    }

    private static UUID uuidV7(long suffix) {
        return UUID.fromString("0198c9d5-0f25-7000-8000-" + String.format("%012d", suffix));
    }
}
