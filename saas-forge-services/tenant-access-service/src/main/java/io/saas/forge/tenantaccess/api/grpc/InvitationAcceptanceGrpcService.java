package io.saas.forge.tenantaccess.api.grpc;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.saas.forge.contracts.tenantaccess.invitation.v1.AcceptInvitationRequest;
import io.saas.forge.contracts.tenantaccess.invitation.v1.AcceptInvitationResponse;
import io.saas.forge.contracts.tenantaccess.invitation.v1.InvitationAcceptanceServiceGrpc;
import io.saas.forge.tenantaccess.application.invitation.InvitationAcceptanceService;
import io.saas.forge.tenantaccess.application.invitation.InvitationFailure;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public final class InvitationAcceptanceGrpcService
        extends InvitationAcceptanceServiceGrpc.InvitationAcceptanceServiceImplBase {
    private final InvitationAcceptanceService acceptance;

    public InvitationAcceptanceGrpcService(InvitationAcceptanceService acceptance) {
        this.acceptance = acceptance;
    }

    @Override public void accept(AcceptInvitationRequest request,
                                 StreamObserver<AcceptInvitationResponse> observer) {
        try {
            var result = acceptance.accept(request.getToken(), canonical(request.getIdentityId()),
                    canonical(request.getIdempotencyKey()), request.getTraceId());
            observer.onNext(AcceptInvitationResponse.newBuilder()
                    .setMembershipId(result.membershipId().toString())
                    .setTenantId(result.tenantId().toString())
                    .setReplayed(result.replayed()).build());
            observer.onCompleted();
        } catch (InvitationFailure failure) {
            Status status = switch (failure.code()) {
                case "INVITATION_INVALID" -> Status.NOT_FOUND;
                case "INVITATION_IDENTITY_MISMATCH", "TENANT_NOT_ACCESSIBLE" -> Status.PERMISSION_DENIED;
                case "INVITATION_DEPENDENCY_UNAVAILABLE", "INVITATION_ACTIVATION_COMPENSATING" ->
                        Status.UNAVAILABLE;
                default -> Status.FAILED_PRECONDITION;
            };
            observer.onError(status.withDescription(failure.code()).asRuntimeException());
        } catch (IllegalArgumentException failure) {
            observer.onError(Status.INVALID_ARGUMENT.withDescription("INVITATION_INVALID").asRuntimeException());
        } catch (RuntimeException unavailable) {
            observer.onError(Status.UNAVAILABLE.withDescription("INVITATION_DEPENDENCY_UNAVAILABLE")
                    .asRuntimeException());
        }
    }

    private static UUID canonical(String value) {
        UUID id = UUID.fromString(value);
        if (id.version() != 7 || !id.toString().equals(value)) throw new IllegalArgumentException();
        return id;
    }
}
