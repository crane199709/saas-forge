package io.saas.forge.tenantaccess.api.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.saas.forge.contracts.tenantaccess.invitation.v1.AcceptInvitationRequest;
import io.saas.forge.contracts.tenantaccess.invitation.v1.AcceptInvitationResponse;
import io.saas.forge.tenantaccess.application.invitation.InvitationAcceptanceService;
import io.saas.forge.tenantaccess.application.invitation.InvitationFailure;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class InvitationAcceptanceGrpcServiceTest {
    private static final UUID IDENTITY = id(1);
    private static final UUID KEY = id(2);
    private static final UUID TENANT = id(3);
    private static final UUID MEMBERSHIP = id(4);

    @Test
    void returnsOnlyAcceptedMembershipAndTenantFromTheVerifiedIdentity() {
        var acceptance = mock(InvitationAcceptanceService.class);
        when(acceptance.accept("invitation-token", IDENTITY, KEY, "trace"))
                .thenReturn(new InvitationAcceptanceService.Result(MEMBERSHIP, TENANT, true));
        var observer = observer();

        new InvitationAcceptanceGrpcService(acceptance).accept(request(), observer);

        var response = ArgumentCaptor.forClass(AcceptInvitationResponse.class);
        verify(observer).onNext(response.capture());
        verify(observer).onCompleted();
        assertEquals(MEMBERSHIP.toString(), response.getValue().getMembershipId());
        assertEquals(TENANT.toString(), response.getValue().getTenantId());
        assertTrue(response.getValue().getReplayed());
    }

    @Test
    void mapsRejectedAndUncertainAcceptanceWithoutExposingRawFailure() {
        for (var case_ : new Object[][] {
                {"INVITATION_INVALID", Status.Code.NOT_FOUND},
                {"INVITATION_IDENTITY_MISMATCH", Status.Code.PERMISSION_DENIED},
                {"TENANT_NOT_ACCESSIBLE", Status.Code.PERMISSION_DENIED},
                {"INVITATION_DEPENDENCY_UNAVAILABLE", Status.Code.UNAVAILABLE},
                {"INVITATION_ACTIVATION_COMPENSATING", Status.Code.UNAVAILABLE},
                {"QUOTA_EXCEEDED", Status.Code.FAILED_PRECONDITION}}) {
            var acceptance = mock(InvitationAcceptanceService.class);
            when(acceptance.accept(eq("invitation-token"), eq(IDENTITY), eq(KEY), anyString()))
                    .thenThrow(new InvitationFailure((String) case_[0]));
            var observer = observer();
            new InvitationAcceptanceGrpcService(acceptance).accept(request(), observer);
            var error = ArgumentCaptor.forClass(Throwable.class);
            verify(observer).onError(error.capture());
            assertEquals(case_[1], Status.fromThrowable(error.getValue()).getCode());
            assertEquals(case_[0], Status.fromThrowable(error.getValue()).getDescription());
        }
    }

    @Test
    void refusesNoncanonicalServiceIdsAndTreatsUnexpectedDependencyFailureAsUnavailable() {
        var acceptance = mock(InvitationAcceptanceService.class);
        var malformed = request().toBuilder().setIdentityId("not-a-uuid").build();
        var observer = observer();
        new InvitationAcceptanceGrpcService(acceptance).accept(malformed, observer);
        var error = ArgumentCaptor.forClass(Throwable.class);
        verify(observer).onError(error.capture());
        assertEquals(Status.Code.INVALID_ARGUMENT, Status.fromThrowable(error.getValue()).getCode());

        when(acceptance.accept("invitation-token", IDENTITY, KEY, "trace"))
                .thenThrow(new IllegalStateException("database detail"));
        var unavailable = observer();
        new InvitationAcceptanceGrpcService(acceptance).accept(request(), unavailable);
        error = ArgumentCaptor.forClass(Throwable.class);
        verify(unavailable).onError(error.capture());
        assertEquals(Status.Code.UNAVAILABLE, Status.fromThrowable(error.getValue()).getCode());
        assertEquals("INVITATION_DEPENDENCY_UNAVAILABLE", Status.fromThrowable(error.getValue()).getDescription());
    }

    private static AcceptInvitationRequest request() {
        return AcceptInvitationRequest.newBuilder().setToken("invitation-token")
                .setIdentityId(IDENTITY.toString()).setIdempotencyKey(KEY.toString())
                .setTraceId("trace").build();
    }

    @SuppressWarnings("unchecked")
    private static StreamObserver<AcceptInvitationResponse> observer() {
        return mock(StreamObserver.class);
    }

    private static UUID id(long value) {
        return UUID.fromString("01991b28-7c00-7000-8000-" + String.format("%012x", value));
    }
}
