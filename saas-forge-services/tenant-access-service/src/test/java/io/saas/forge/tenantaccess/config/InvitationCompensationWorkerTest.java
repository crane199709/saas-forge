package io.saas.forge.tenantaccess.config;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.saas.forge.tenantaccess.application.invitation.InvitationAcceptanceService;
import org.junit.jupiter.api.Test;

class InvitationCompensationWorkerTest {
    @Test
    void retriesCommittedQuotaCompensationUntilQueueIsEmpty() {
        var acceptance = mock(InvitationAcceptanceService.class);
        when(acceptance.recoverNext()).thenReturn(true, true, false);

        new InvitationConfiguration.InvitationCompensationWorker(acceptance).recover();

        verify(acceptance, times(3)).recoverNext();
    }

    @Test
    void boundsRecoveryAndLeavesUnavailableDependencyForTheNextTick() {
        var pending = mock(InvitationAcceptanceService.class);
        when(pending.recoverNext()).thenReturn(true);
        new InvitationConfiguration.InvitationCompensationWorker(pending).recover();
        verify(pending, times(100)).recoverNext();

        var unavailable = mock(InvitationAcceptanceService.class);
        when(unavailable.recoverNext()).thenThrow(new IllegalStateException("quota offline"));
        new InvitationConfiguration.InvitationCompensationWorker(unavailable).recover();
        verify(unavailable).recoverNext();
    }
}
