package io.saas.forge.iam.infrastructure.grpc;

import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import io.saas.forge.contracts.tenantaccess.invitation.v1.AcceptInvitationRequest;
import io.saas.forge.contracts.tenantaccess.invitation.v1.InvitationAcceptanceServiceGrpc;
import io.saas.forge.iam.application.authentication.InvitationAcceptanceFailure;
import io.saas.forge.iam.application.authentication.InvitationAcceptanceGateway;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

public final class GrpcInvitationAcceptanceGateway implements InvitationAcceptanceGateway {
    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
    private static final Set<String> KNOWN = Set.of("INVITATION_INVALID", "INVITATION_IDENTITY_MISMATCH",
            "TENANT_NOT_ACCESSIBLE", "INVITATION_REVOKED", "INVITATION_EXPIRED",
            "INVITATION_ALREADY_ACCEPTED", "INVITATION_NOT_PENDING", "MEMBERSHIP_ALREADY_EXISTS",
            "INVITATION_ACCEPTANCE_IN_PROGRESS", "INVITATION_ACTIVATION_COMPENSATING",
            "INVITATION_ACCEPTANCE_RETRY_REQUIRED", "QUOTA_EXCEEDED", "SUBSCRIPTION_REQUIRED",
            "INVITATION_DEPENDENCY_UNAVAILABLE");
    private final InvitationAcceptanceServiceGrpc.InvitationAcceptanceServiceBlockingStub client;
    private final Supplier<String> accessToken;

    public GrpcInvitationAcceptanceGateway(
            InvitationAcceptanceServiceGrpc.InvitationAcceptanceServiceBlockingStub client,
            Supplier<String> accessToken) {
        this.client = client; this.accessToken = accessToken;
    }

    @Override public Result accept(String token, UUID identityId, UUID key, String traceId) {
        try {
            Metadata headers = new Metadata();
            headers.put(AUTHORIZATION, "Bearer " + accessToken.get());
            var response = client.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
                    .accept(AcceptInvitationRequest.newBuilder().setToken(token)
                            .setIdentityId(identityId.toString()).setIdempotencyKey(key.toString())
                            .setTraceId(traceId == null ? "" : traceId).build());
            return new Result(UUID.fromString(response.getMembershipId()), UUID.fromString(response.getTenantId()),
                    response.getReplayed());
        } catch (StatusRuntimeException failure) {
            String code = failure.getStatus().getDescription();
            if (code != null && KNOWN.contains(code)
                    && Set.of(Status.Code.NOT_FOUND, Status.Code.PERMISSION_DENIED,
                            Status.Code.FAILED_PRECONDITION, Status.Code.UNAVAILABLE)
                            .contains(failure.getStatus().getCode()))
                throw new InvitationAcceptanceFailure(code);
            throw new InvitationAcceptanceFailure("INVITATION_DEPENDENCY_UNAVAILABLE");
        } catch (RuntimeException unavailable) {
            if (unavailable instanceof InvitationAcceptanceFailure known) throw known;
            throw new InvitationAcceptanceFailure("INVITATION_DEPENDENCY_UNAVAILABLE");
        }
    }
}
