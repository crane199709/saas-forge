package io.saas.forge.tenantaccess.infrastructure.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.ClientInterceptor;
import io.grpc.Status;
import io.saas.forge.contracts.entitlement.quota.v1.QuotaCommandRequest;
import io.saas.forge.contracts.entitlement.quota.v1.QuotaCommandServiceGrpc;
import io.saas.forge.contracts.entitlement.quota.v1.QuotaPurpose;
import io.saas.forge.contracts.iam.passwordsetup.v1.DeliverPasswordSetupRequest;
import io.saas.forge.contracts.iam.passwordsetup.v1.DeliverPasswordSetupResponse;
import io.saas.forge.contracts.iam.passwordsetup.v1.PasswordSetupDeliveryResult;
import io.saas.forge.contracts.iam.passwordsetup.v1.PasswordSetupServiceGrpc;
import io.saas.forge.contracts.iam.session.v1.RecoverUserSessionRevocationRequest;
import io.saas.forge.contracts.iam.session.v1.ReleaseUserSessionFenceRequest;
import io.saas.forge.contracts.iam.session.v1.RevokeUserSessionsRequest;
import io.saas.forge.contracts.iam.session.v1.RevokeUserSessionsResponse;
import io.saas.forge.contracts.iam.session.v1.SessionRevocationCompleted;
import io.saas.forge.contracts.iam.session.v1.SessionRevocationPending;
import io.saas.forge.contracts.iam.session.v1.UserSessionRevocationServiceGrpc;
import io.saas.forge.tenantaccess.application.administrator.QuotaUnavailableException;
import io.saas.forge.tenantaccess.application.administrator.RemoteWorkflowUnavailableException;
import io.saas.forge.tenantaccess.application.invitation.InvitationPasswordSetupGateway;
import io.saas.forge.tenantaccess.application.membership.MembershipQuotaExceededException;
import io.saas.forge.tenantaccess.application.tenant.SessionRevocationGateway;
import io.saas.forge.tenantaccess.application.tenant.SessionRevocationRejectedException;
import io.saas.forge.tenantaccess.application.tenant.SessionRevocationUnavailableException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GrpcStage4WorkflowGatewaysTest {
    private static final UUID TENANT = id(1);
    private static final UUID MEMBERSHIP = id(2);
    private static final UUID IDENTITY = id(3);
    private static final UUID OPERATION = id(4);
    private static final UUID OTHER = id(5);

    @Test
    void invitationQuotaUsesOneStableOperationForConsumeAndCompensation() {
        var stub = mock(QuotaCommandServiceGrpc.QuotaCommandServiceBlockingStub.class);
        var authorized = mock(QuotaCommandServiceGrpc.QuotaCommandServiceBlockingStub.class);
        when(stub.withInterceptors(any(ClientInterceptor.class))).thenReturn(authorized);
        var gateway = new GrpcInvitationQuotaGateway(stub, () -> "service-token");

        gateway.consume(TENANT, OPERATION);
        gateway.release(TENANT, OPERATION);

        var command = quota(QuotaPurpose.INVITATION_ACCEPTANCE);
        verify(authorized).consume(command);
        verify(authorized).release(command);
    }

    @Test
    void invitationQuotaDistinguishesCapacityAndSubscriptionFromUncertainRemoteFailure() {
        var stub = mock(QuotaCommandServiceGrpc.QuotaCommandServiceBlockingStub.class);
        var authorized = mock(QuotaCommandServiceGrpc.QuotaCommandServiceBlockingStub.class);
        when(stub.withInterceptors(any(ClientInterceptor.class))).thenReturn(authorized);
        var gateway = new GrpcInvitationQuotaGateway(stub, () -> "service-token");
        doThrow(Status.RESOURCE_EXHAUSTED.withDescription("QUOTA_EXCEEDED").asRuntimeException())
                .when(authorized).consume(any());
        assertEquals("QUOTA_EXCEEDED", assertThrows(QuotaUnavailableException.class,
                () -> gateway.consume(TENANT, OPERATION)).getMessage());
        doThrow(Status.FAILED_PRECONDITION.withDescription("SUBSCRIPTION_REQUIRED").asRuntimeException())
                .when(authorized).consume(any());
        assertEquals("SUBSCRIPTION_REQUIRED", assertThrows(QuotaUnavailableException.class,
                () -> gateway.consume(TENANT, OPERATION)).getMessage());
        doThrow(Status.RESOURCE_EXHAUSTED.withDescription("OTHER").asRuntimeException())
                .when(authorized).consume(any());
        assertThrows(RemoteWorkflowUnavailableException.class, () -> gateway.consume(TENANT, OPERATION));
        doThrow(Status.RESOURCE_EXHAUSTED.withDescription("QUOTA_EXCEEDED").asRuntimeException())
                .when(authorized).release(any());
        assertThrows(RemoteWorkflowUnavailableException.class, () -> gateway.release(TENANT, OPERATION));
        assertThrows(RemoteWorkflowUnavailableException.class,
                () -> new GrpcInvitationQuotaGateway(stub, () -> {
                    throw new IllegalStateException("identity unavailable");
                }).consume(TENANT, OPERATION));
    }

    @Test
    void invitationPasswordSetupKeepsCredentialDispositionAndIdentityBoundary() {
        var stub = mock(PasswordSetupServiceGrpc.PasswordSetupServiceBlockingStub.class);
        var authorized = mock(PasswordSetupServiceGrpc.PasswordSetupServiceBlockingStub.class);
        when(stub.withInterceptors(any(ClientInterceptor.class))).thenReturn(authorized);
        var gateway = new GrpcInvitationPasswordSetupGateway(stub, () -> "service-token");
        var request = DeliverPasswordSetupRequest.newBuilder()
                .setRequestId(OPERATION.toString()).setIdentityId(IDENTITY.toString()).build();
        when(authorized.deliverPasswordSetup(request)).thenReturn(
                DeliverPasswordSetupResponse.newBuilder().setResult(PasswordSetupDeliveryResult.DELIVERED).build());
        assertEquals(InvitationPasswordSetupGateway.Result.MAIL_SERVICE_ACCEPTED,
                gateway.request(OPERATION, IDENTITY));
        when(authorized.deliverPasswordSetup(request)).thenReturn(
                DeliverPasswordSetupResponse.newBuilder().setResult(PasswordSetupDeliveryResult.PASSWORD_READY).build());
        assertEquals(InvitationPasswordSetupGateway.Result.PASSWORD_READY, gateway.request(OPERATION, IDENTITY));
        verify(authorized, times(2)).deliverPasswordSetup(request);
    }

    @Test
    void invitationPasswordSetupRequiresRecoveryInsteadOfReplacingAnExistingCredential() {
        var stub = mock(PasswordSetupServiceGrpc.PasswordSetupServiceBlockingStub.class);
        var authorized = mock(PasswordSetupServiceGrpc.PasswordSetupServiceBlockingStub.class);
        when(stub.withInterceptors(any(ClientInterceptor.class))).thenReturn(authorized);
        var gateway = new GrpcInvitationPasswordSetupGateway(stub, () -> "service-token");
        doThrow(Status.FAILED_PRECONDITION.asRuntimeException()).when(authorized).deliverPasswordSetup(any());
        assertEquals(InvitationPasswordSetupGateway.Result.RECOVERY_REQUIRED, gateway.request(OPERATION, IDENTITY));
        doThrow(Status.UNAVAILABLE.asRuntimeException()).when(authorized).deliverPasswordSetup(any());
        assertThrows(RemoteWorkflowUnavailableException.class, () -> gateway.request(OPERATION, IDENTITY));
        doReturn(DeliverPasswordSetupResponse.getDefaultInstance())
                .when(authorized).deliverPasswordSetup(any());
        assertThrows(RemoteWorkflowUnavailableException.class, () -> gateway.request(OPERATION, IDENTITY));
        assertThrows(RemoteWorkflowUnavailableException.class,
                () -> new GrpcInvitationPasswordSetupGateway(stub, () -> {
                    throw new IllegalStateException("token unavailable");
                }).request(OPERATION, IDENTITY));
    }

    @Test
    void membershipEnableQuotaUsesItsOwnPurposeAndDistinguishesCapacityFromTransportFailure() {
        var stub = mock(QuotaCommandServiceGrpc.QuotaCommandServiceBlockingStub.class);
        var authorized = mock(QuotaCommandServiceGrpc.QuotaCommandServiceBlockingStub.class);
        when(stub.withInterceptors(any(ClientInterceptor.class))).thenReturn(authorized);
        var gateway = new GrpcMembershipEnableQuotaGateway(stub, () -> "service-token");
        gateway.consume(TENANT, OPERATION);
        gateway.release(TENANT, OPERATION);
        verify(authorized).consume(quota(QuotaPurpose.MEMBERSHIP_ENABLE));
        verify(authorized).release(quota(QuotaPurpose.MEMBERSHIP_ENABLE));
        doThrow(Status.RESOURCE_EXHAUSTED.asRuntimeException()).when(authorized).consume(any());
        assertThrows(MembershipQuotaExceededException.class, () -> gateway.consume(TENANT, OPERATION));
        doThrow(Status.UNAVAILABLE.asRuntimeException()).when(authorized).consume(any());
        assertThrows(RemoteWorkflowUnavailableException.class, () -> gateway.consume(TENANT, OPERATION));
        doThrow(Status.FAILED_PRECONDITION.asRuntimeException()).when(authorized).release(any());
        assertThrows(RemoteWorkflowUnavailableException.class, () -> gateway.release(TENANT, OPERATION));
    }

    @Test
    void membershipDisableReleasesOnlyItsOwnQuotaOperationAndPropagatesUncertainFailure() {
        var stub = mock(QuotaCommandServiceGrpc.QuotaCommandServiceBlockingStub.class);
        var authorized = mock(QuotaCommandServiceGrpc.QuotaCommandServiceBlockingStub.class);
        when(stub.withInterceptors(any(ClientInterceptor.class))).thenReturn(authorized);
        var gateway = new GrpcMembershipQuotaGateway(stub, () -> "service-token");
        gateway.release(TENANT, OPERATION);
        verify(authorized).release(quota(QuotaPurpose.MEMBERSHIP_DISABLE));
        doThrow(Status.UNAVAILABLE.asRuntimeException()).when(authorized).release(any());
        assertThrows(RemoteWorkflowUnavailableException.class, () -> gateway.release(TENANT, OPERATION));
    }

    @Test
    void membershipEnableFenceReleasesOnlyTheSpecifiedRevocationAndFailsClosed() {
        var stub = mock(UserSessionRevocationServiceGrpc.UserSessionRevocationServiceBlockingStub.class);
        var authorized = mock(UserSessionRevocationServiceGrpc.UserSessionRevocationServiceBlockingStub.class);
        when(stub.withInterceptors(any(ClientInterceptor.class))).thenReturn(authorized);
        var gateway = new GrpcMembershipEnableFenceGateway(stub, () -> "service-token");
        gateway.release(OPERATION, OTHER, TENANT, MEMBERSHIP);
        var request = ReleaseUserSessionFenceRequest.newBuilder()
                .setReleaseRequestId(OPERATION.toString()).setRevocationRequestId(OTHER.toString())
                .setMembershipTarget(membershipTarget()).build();
        verify(authorized).releaseUserSessionFence(request);
        doThrow(Status.FAILED_PRECONDITION.asRuntimeException()).when(authorized).releaseUserSessionFence(any());
        assertThrows(SessionRevocationRejectedException.class,
                () -> gateway.release(OPERATION, OTHER, TENANT, MEMBERSHIP));
        doThrow(Status.UNAVAILABLE.asRuntimeException()).when(authorized).releaseUserSessionFence(any());
        assertThrows(SessionRevocationUnavailableException.class,
                () -> gateway.release(OPERATION, OTHER, TENANT, MEMBERSHIP));
    }

    @Test
    void membershipRevocationTargetsOnlyOneMembershipAndRetainsRetryOutcome() {
        var stub = mock(UserSessionRevocationServiceGrpc.UserSessionRevocationServiceBlockingStub.class);
        var authorized = mock(UserSessionRevocationServiceGrpc.UserSessionRevocationServiceBlockingStub.class);
        when(stub.withInterceptors(any(ClientInterceptor.class))).thenReturn(authorized);
        var gateway = new GrpcMembershipRevocationGateway(stub, () -> "service-token");
        var request = RevokeUserSessionsRequest.newBuilder().setRequestId(OPERATION.toString())
                .setMembershipTarget(membershipTarget()).build();
        when(authorized.revokeUserSessions(request)).thenReturn(RevokeUserSessionsResponse.newBuilder()
                .setPending(SessionRevocationPending.newBuilder().setRetryAfterSeconds(5)).build());
        assertEquals(SessionRevocationGateway.Result.pending(5), gateway.revoke(OPERATION, TENANT, MEMBERSHIP));
        when(authorized.revokeUserSessions(request)).thenReturn(RevokeUserSessionsResponse.newBuilder()
                .setCompleted(SessionRevocationCompleted.newBuilder().setRevokedFamilyCount(2)
                        .setRevokedJtiCount(4)).build());
        assertEquals(SessionRevocationGateway.Result.completed(2, 4),
                gateway.revoke(OPERATION, TENANT, MEMBERSHIP));
        verify(authorized, times(2)).revokeUserSessions(request);
        gateway.recover(OPERATION, TENANT, MEMBERSHIP);
        verify(authorized).recoverUserSessionRevocation(RecoverUserSessionRevocationRequest.newBuilder()
                .setRevocationRequestId(OPERATION.toString()).setMembershipTarget(membershipTarget()).build());
    }

    @Test
    void membershipRevocationRejectsMissingOutcomeAndMapsRecoverableFailure() {
        var stub = mock(UserSessionRevocationServiceGrpc.UserSessionRevocationServiceBlockingStub.class);
        var authorized = mock(UserSessionRevocationServiceGrpc.UserSessionRevocationServiceBlockingStub.class);
        when(stub.withInterceptors(any(ClientInterceptor.class))).thenReturn(authorized);
        var gateway = new GrpcMembershipRevocationGateway(stub, () -> "service-token");
        when(authorized.revokeUserSessions(any())).thenReturn(RevokeUserSessionsResponse.getDefaultInstance());
        assertThrows(IllegalStateException.class, () -> gateway.revoke(OPERATION, TENANT, MEMBERSHIP));
        doThrow(Status.UNAVAILABLE.asRuntimeException()).when(authorized).revokeUserSessions(any());
        assertThrows(SessionRevocationUnavailableException.class,
                () -> gateway.revoke(OPERATION, TENANT, MEMBERSHIP));
        doThrow(Status.DEADLINE_EXCEEDED.asRuntimeException()).when(authorized).recoverUserSessionRevocation(any());
        assertThrows(SessionRevocationUnavailableException.class,
                () -> gateway.recover(OPERATION, TENANT, MEMBERSHIP));
        doThrow(Status.FAILED_PRECONDITION.asRuntimeException()).when(authorized).recoverUserSessionRevocation(any());
        assertThrows(SessionRevocationRejectedException.class,
                () -> gateway.recover(OPERATION, TENANT, MEMBERSHIP));
        doThrow(Status.INVALID_ARGUMENT.asRuntimeException()).when(authorized).revokeUserSessions(any());
        assertThrows(IllegalStateException.class, () -> gateway.revoke(OPERATION, TENANT, MEMBERSHIP));
    }

    private static QuotaCommandRequest quota(QuotaPurpose purpose) {
        return QuotaCommandRequest.newBuilder().setTenantId(TENANT.toString())
                .setQuotaCode("max_users").setAmount(1).setOperationId(OPERATION.toString())
                .setPurpose(purpose).build();
    }

    private static io.saas.forge.contracts.iam.session.v1.MembershipTarget membershipTarget() {
        return io.saas.forge.contracts.iam.session.v1.MembershipTarget.newBuilder()
                .setTenantId(TENANT.toString()).setMembershipId(MEMBERSHIP.toString()).build();
    }

    private static UUID id(long value) {
        return UUID.fromString("01991b28-7c00-7000-8000-" + String.format("%012x", value));
    }
}
