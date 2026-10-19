package io.saas.forge.iam.application.authorization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.saas.forge.iam.application.authentication.PasswordSetupDeliveryResult;
import io.saas.forge.iam.application.authentication.PasswordSetupDeliveryService;
import io.saas.forge.iam.domain.identity.IdentityCredentialStatus;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class PlatformAdminGrantServiceTest {
    private static final UUID ACTOR = UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4c8f");
    private static final UUID CALLER = UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4c8e");
    private static final UUID REQUEST = UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4c90");
    private static final UUID IDENTITY = UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4c91");
    private static final UUID ASSIGNMENT = UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4c92");

    @Test
    void newIdentityReceivesSetupAfterRoleFactIsCommitted() {
        PlatformAdminGrantTransaction transaction = Mockito.mock(PlatformAdminGrantTransaction.class);
        PasswordSetupDeliveryService deliveries = Mockito.mock(PasswordSetupDeliveryService.class);
        when(transaction.claimDelivery(REQUEST)).thenReturn(true);
        when(transaction.grant(ACTOR, REQUEST, "admin@example.test", null))
                .thenReturn(new PlatformAdminGrant(IDENTITY, ASSIGNMENT,
                        IdentityCredentialStatus.SETUP_ALLOWED, true, CALLER, null));
        when(deliveries.deliver(CALLER, REQUEST, IDENTITY, null)).thenReturn(PasswordSetupDeliveryResult.DELIVERED);

        var result = new PlatformAdminGrantService(transaction, deliveries)
                .grant(ACTOR, REQUEST, "admin@example.test", null);

        assertEquals(PlatformAdminGrantResult.SetupState.DELIVERED, result.setupState());
        verify(deliveries).deliver(CALLER, REQUEST, IDENTITY, null);
        verify(transaction).completeDelivery(REQUEST, PlatformAdminGrantResult.SetupState.DELIVERED);
    }

    @Test
    void existingPasswordAndHistoricalCredentialNeverReceiveSetup() {
        PlatformAdminGrantTransaction transaction = Mockito.mock(PlatformAdminGrantTransaction.class);
        PasswordSetupDeliveryService deliveries = Mockito.mock(PasswordSetupDeliveryService.class);
        var service = new PlatformAdminGrantService(transaction, deliveries);
        when(transaction.grant(ACTOR, REQUEST, "admin@example.test", null))
                .thenReturn(new PlatformAdminGrant(IDENTITY, ASSIGNMENT,
                        IdentityCredentialStatus.PASSWORD_READY, true, null, null));
        assertEquals(PlatformAdminGrantResult.SetupState.PASSWORD_READY,
                service.grant(ACTOR, REQUEST, "admin@example.test", null).setupState());
        when(transaction.grant(ACTOR, REQUEST, "admin@example.test", null))
                .thenReturn(new PlatformAdminGrant(IDENTITY, ASSIGNMENT,
                        IdentityCredentialStatus.RECOVERY_REQUIRED, true, null, null));
        assertEquals(PlatformAdminGrantResult.SetupState.RECOVERY_REQUIRED,
                service.grant(ACTOR, REQUEST, "admin@example.test", null).setupState());
        verify(deliveries, never()).deliver(CALLER, REQUEST, IDENTITY, null);
    }

    @Test
    void deliveryFailureDoesNotTurnSmtpAcceptanceIntoPasswordEstablished() {
        PlatformAdminGrantTransaction transaction = Mockito.mock(PlatformAdminGrantTransaction.class);
        PasswordSetupDeliveryService deliveries = Mockito.mock(PasswordSetupDeliveryService.class);
        when(transaction.claimDelivery(REQUEST)).thenReturn(true);
        when(transaction.grant(ACTOR, REQUEST, "admin@example.test", null))
                .thenReturn(new PlatformAdminGrant(IDENTITY, ASSIGNMENT,
                        IdentityCredentialStatus.SETUP_ALLOWED, true, CALLER, null));
        when(deliveries.deliver(CALLER, REQUEST, IDENTITY, null)).thenThrow(new IllegalStateException());
        assertThrows(IllegalStateException.class,
                () -> new PlatformAdminGrantService(transaction, deliveries)
                        .grant(ACTOR, REQUEST, "admin@example.test", null));
    }

    @Test
    void unfinishedGrantCanBeRecoveredAfterProcessRestart() {
        PlatformAdminGrantTransaction transaction = Mockito.mock(PlatformAdminGrantTransaction.class);
        PasswordSetupDeliveryService deliveries = Mockito.mock(PasswordSetupDeliveryService.class);
        when(transaction.pendingDeliveries()).thenReturn(java.util.List.of(
                new PlatformAdminGrantTransaction.DeliveryWork(REQUEST, IDENTITY, CALLER)));
        when(transaction.claimDelivery(REQUEST)).thenReturn(true);
        when(deliveries.deliver(CALLER, REQUEST, IDENTITY, null)).thenReturn(PasswordSetupDeliveryResult.DELIVERED);

        new PlatformAdminGrantService(transaction, deliveries).recoverPending();

        verify(transaction).completeDelivery(REQUEST, PlatformAdminGrantResult.SetupState.DELIVERED);
    }
}
