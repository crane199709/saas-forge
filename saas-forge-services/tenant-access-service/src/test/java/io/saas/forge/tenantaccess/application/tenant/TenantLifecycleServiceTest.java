package io.saas.forge.tenantaccess.application.tenant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.saas.forge.tenantaccess.domain.tenant.TenantStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TenantLifecycleServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-26T06:00:00Z");
    private static final UUID WORKFLOW_ID = uuidV7(1);
    private static final UUID TENANT_ID = uuidV7(2);
    private static final UUID ACTOR_ID = uuidV7(3);
    private static final UUID IDEMPOTENCY_KEY = uuidV7(4);
    private static final UUID REVOCATION_REQUEST_ID = uuidV7(5);
    private static final UUID RELEASE_REQUEST_ID = uuidV7(6);

    private TenantLifecycleRepository workflows;
    private SessionRevocationGateway revocations;
    private UuidV7Generator ids;
    private TenantLifecycleService service;

    @BeforeEach
    void setUp() {
        workflows = mock(TenantLifecycleRepository.class);
        revocations = mock(SessionRevocationGateway.class);
        ids = mock(UuidV7Generator.class);
        service = new TenantLifecycleService(
                workflows,
                revocations,
                mock(TenantSuspendedEventFactory.class),
                ids,
                new TenantLifecycleRecoveryPolicy(Duration.ofSeconds(30), Duration.ofSeconds(1), 10),
                Clock.fixed(NOW, ZoneOffset.UTC),
                "worker-test");
    }

    @Test
    void lifecycleProgressDoesNotOfferResumeWhileSuspensionIsPending() {
        var tenant = new io.saas.forge.tenantaccess.domain.tenant.Tenant(
                TENANT_ID, "Example", TenantStatus.ACTIVE, null, NOW, NOW);
        when(workflows.readLifecycle(TENANT_ID)).thenReturn(
                new TenantLifecycleSnapshot(tenant, Optional.of(pendingWorkflow())));
        var progress = service.read(TENANT_ID);
        assertEquals("PENDING", progress.state());
        assertEquals(false, progress.canResume());
        assertEquals(false, progress.canSuspend());
        assertEquals(WORKFLOW_ID, progress.operationId());
    }

    @Test
    void continuesOnlyTheLatestDurableOperationWithoutPreparingAnotherWorkflow() {
        var tenant = new io.saas.forge.tenantaccess.domain.tenant.Tenant(
                TENANT_ID, "Example", TenantStatus.SUSPENDED, null, NOW, NOW);
        var result = new TenantLifecycleResult(TENANT_ID, "Example", TenantStatus.SUSPENDED, null, NOW, NOW);
        var completed = workflow(TenantLifecycleAction.SUSPEND, TenantLifecycleStatus.COMPLETED, null, result);
        when(workflows.readLifecycle(TENANT_ID)).thenReturn(new TenantLifecycleSnapshot(tenant, Optional.of(completed)));
        assertEquals(result, service.continueOperation(TENANT_ID, WORKFLOW_ID, null));
        var rejected = assertThrows(TenantLifecycleException.class,
                () -> service.continueOperation(TENANT_ID, IDEMPOTENCY_KEY, null));
        assertEquals("TENANT_LIFECYCLE_OPERATION_UNAVAILABLE", rejected.code());
        org.mockito.Mockito.verify(workflows, org.mockito.Mockito.never()).claim(any(), any(), any(), any(), anyInt());
    }

    @Test
    void emptyHistoryAllowsSuspensionButNeverPretendsAnOperationCanContinue() {
        var tenant = new io.saas.forge.tenantaccess.domain.tenant.Tenant(
                TENANT_ID, "Example", TenantStatus.ACTIVE, null, NOW, NOW);
        when(workflows.readLifecycle(TENANT_ID)).thenReturn(new TenantLifecycleSnapshot(tenant, Optional.empty()));
        var progress = service.read(TENANT_ID);
        assertEquals("NONE", progress.state());
        assertEquals(true, progress.canSuspend());
        assertEquals(false, progress.canContinue());
        assertThrows(TenantLifecycleException.class, () -> service.continueOperation(TENANT_ID, WORKFLOW_ID, null));
    }

    @Test
    void closureRequiresTargetRevocationBeforeReportingClosed() {
        TenantLifecycleWorkflow pending = workflow(TenantLifecycleAction.CLOSE, TenantLifecycleStatus.PENDING, null, null);
        when(ids.next()).thenReturn(WORKFLOW_ID, REVOCATION_REQUEST_ID);
        when(workflows.prepare(eq(ACTOR_ID), eq(IDEMPOTENCY_KEY), eq(TENANT_ID),
                eq(TenantLifecycleAction.CLOSE), anyString(), eq(WORKFLOW_ID),
                eq(REVOCATION_REQUEST_ID), isNull(), eq(NOW)))
                .thenReturn(new TenantLifecycleClaim(TenantLifecycleClaim.Status.CREATED, pending));
        when(workflows.claim(WORKFLOW_ID, "worker-test", NOW, NOW.plusSeconds(30), 10))
                .thenReturn(Optional.of(pending));
        when(revocations.revoke(REVOCATION_REQUEST_ID, TENANT_ID))
                .thenReturn(SessionRevocationGateway.Result.pending(4));

        TenantLifecycleException error = assertThrows(TenantLifecycleException.class,
                () -> service.close(ACTOR_ID, IDEMPOTENCY_KEY, TENANT_ID, null));
        assertEquals("TENANT_CLOSURE_PENDING", error.code());
        org.mockito.Mockito.verify(workflows, org.mockito.Mockito.never())
                .complete(any(), any(), org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong(), any(), any());
    }

    @Test
    void workerKeepsRecoverableFailureAndBusinessPendingNonInteractive() {
        TenantLifecycleWorkflow workflow = pendingWorkflow();
        when(workflows.claimNext(any(), any(), any(), anyInt()))
                .thenReturn(Optional.of(workflow), Optional.of(workflow));
        when(workflows.find(WORKFLOW_ID)).thenReturn(Optional.of(workflow));
        when(revocations.revoke(REVOCATION_REQUEST_ID, TENANT_ID))
                .thenThrow(new SessionRevocationUnavailableException(new RuntimeException()))
                .thenReturn(SessionRevocationGateway.Result.pending(4));

        assertDoesNotThrow(service::recoverNext);
        assertDoesNotThrow(service::recoverNext);
    }

    @Test
    void rejectsMissingAndNonV7LifecycleIdentifiers() {
        assertThrows(IllegalArgumentException.class,
                () -> service.suspend(null, IDEMPOTENCY_KEY, TENANT_ID, null));
        assertThrows(IdempotencyKeyInvalidException.class,
                () -> service.suspend(ACTOR_ID, UUID.randomUUID(), TENANT_ID, null));
        assertThrows(IllegalArgumentException.class,
                () -> service.resume(ACTOR_ID, IDEMPOTENCY_KEY, UUID.randomUUID()));
    }

    @Test
    void returnsTerminalReplayAfterAnotherClaimantCompletes() {
        TenantLifecycleWorkflow pending = pendingWorkflow();
        TenantLifecycleResult result = new TenantLifecycleResult(
                TENANT_ID, "Tenant", TenantStatus.SUSPENDED, null, NOW, NOW);
        TenantLifecycleWorkflow completed = workflow(
                TenantLifecycleAction.SUSPEND, TenantLifecycleStatus.COMPLETED, null, result);
        stubSuspendPreparation(pending);
        when(workflows.claim(WORKFLOW_ID, "worker-test", NOW, NOW.plusSeconds(30), 10))
                .thenReturn(Optional.empty());
        when(workflows.find(WORKFLOW_ID)).thenReturn(Optional.of(completed));

        assertEquals(result, service.suspend(ACTOR_ID, IDEMPOTENCY_KEY, TENANT_ID, null));
    }

    @Test
    void interactiveUnexpectedRejectionReturnsStablePendingOutcome() {
        TenantLifecycleWorkflow pending = pendingWorkflow();
        stubSuspendPreparation(pending);
        when(workflows.claim(WORKFLOW_ID, "worker-test", NOW, NOW.plusSeconds(30), 10))
                .thenReturn(Optional.of(pending));
        when(workflows.find(WORKFLOW_ID)).thenReturn(Optional.of(pending));
        when(revocations.revoke(REVOCATION_REQUEST_ID, TENANT_ID))
                .thenThrow(new SessionRevocationRejectedException());

        TenantLifecycleException exception = assertThrows(TenantLifecycleException.class,
                () -> service.suspend(ACTOR_ID, IDEMPOTENCY_KEY, TENANT_ID, null));
        assertEquals("TENANT_SUSPENSION_PENDING", exception.code());
    }

    @Test
    void distinguishesResumeAndExplicitRecoveryPendingReplays() {
        TenantLifecycleWorkflow resume = workflow(
                TenantLifecycleAction.RESUME, TenantLifecycleStatus.PENDING, null, null);
        when(ids.next()).thenReturn(WORKFLOW_ID, RELEASE_REQUEST_ID);
        when(workflows.prepare(
                eq(ACTOR_ID), eq(IDEMPOTENCY_KEY), eq(TENANT_ID),
                eq(TenantLifecycleAction.RESUME), anyString(), eq(WORKFLOW_ID),
                isNull(), eq(RELEASE_REQUEST_ID), eq(NOW)))
                .thenReturn(new TenantLifecycleClaim(TenantLifecycleClaim.Status.CREATED, resume));
        when(workflows.claim(WORKFLOW_ID, "worker-test", NOW, NOW.plusSeconds(30), 10))
                .thenReturn(Optional.empty());
        when(workflows.find(WORKFLOW_ID)).thenReturn(Optional.of(resume));
        TenantLifecycleException resumePending = assertThrows(TenantLifecycleException.class,
                () -> service.resume(ACTOR_ID, IDEMPOTENCY_KEY, TENANT_ID));
        assertEquals("TENANT_RESUME_PENDING", resumePending.code());

        TenantLifecycleWorkflow recovery = workflow(
                TenantLifecycleAction.SUSPEND, TenantLifecycleStatus.PENDING, NOW, null);
        when(workflows.prepareRecovery(
                eq(ACTOR_ID), eq(IDEMPOTENCY_KEY), eq(TENANT_ID), anyString(), eq(NOW)))
                .thenReturn(new TenantLifecycleClaim(TenantLifecycleClaim.Status.RECOVERY_STARTED, recovery));
        when(workflows.find(WORKFLOW_ID)).thenReturn(Optional.of(recovery));
        TenantLifecycleException recoveryPending = assertThrows(TenantLifecycleException.class,
                () -> service.recoverSuspension(ACTOR_ID, IDEMPOTENCY_KEY, TENANT_ID, null));
        assertEquals("TENANT_SUSPENSION_RECOVERY_PENDING", recoveryPending.code());
    }

    private void stubSuspendPreparation(TenantLifecycleWorkflow workflow) {
        when(ids.next()).thenReturn(WORKFLOW_ID, REVOCATION_REQUEST_ID);
        when(workflows.prepare(
                eq(ACTOR_ID), eq(IDEMPOTENCY_KEY), eq(TENANT_ID),
                eq(TenantLifecycleAction.SUSPEND), anyString(), eq(WORKFLOW_ID),
                eq(REVOCATION_REQUEST_ID), isNull(), eq(NOW)))
                .thenReturn(new TenantLifecycleClaim(TenantLifecycleClaim.Status.CREATED, workflow));
    }

    private static TenantLifecycleWorkflow pendingWorkflow() {
        return workflow(TenantLifecycleAction.SUSPEND, TenantLifecycleStatus.PENDING, null, null);
    }

    private static TenantLifecycleWorkflow workflow(
            TenantLifecycleAction action,
            TenantLifecycleStatus status,
            Instant recoveryStartedAt,
            TenantLifecycleResult result) {
        return new TenantLifecycleWorkflow(
                WORKFLOW_ID,
                TENANT_ID,
                ACTOR_ID,
                IDEMPOTENCY_KEY,
                "a".repeat(64),
                action,
                REVOCATION_REQUEST_ID,
                action == TenantLifecycleAction.RESUME ? RELEASE_REQUEST_ID : null,
                status,
                false,
                0,
                0,
                1,
                NOW,
                "worker-test",
                NOW.plusSeconds(30),
                1,
                recoveryStartedAt,
                null,
                status == TenantLifecycleStatus.COMPLETED ? NOW : null,
                result);
    }

    private static UUID uuidV7(long value) {
        return UUID.fromString("01991b28-7c00-7000-8000-" + String.format("%012x", value));
    }
}
