package io.saas.forge.tenantaccess.infrastructure.grpc;

import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import io.saas.forge.contracts.iam.session.v1.MembershipTarget;
import io.saas.forge.contracts.iam.session.v1.ReleaseUserSessionFenceRequest;
import io.saas.forge.contracts.iam.session.v1.UserSessionRevocationServiceGrpc;
import io.saas.forge.tenantaccess.application.membership.MembershipEnableFenceGateway;
import io.saas.forge.tenantaccess.application.tenant.SessionRevocationRejectedException;
import io.saas.forge.tenantaccess.application.tenant.SessionRevocationUnavailableException;
import java.util.UUID;
import java.util.function.Supplier;

public final class GrpcMembershipEnableFenceGateway implements MembershipEnableFenceGateway {
    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
    private final UserSessionRevocationServiceGrpc.UserSessionRevocationServiceBlockingStub client;
    private final Supplier<String> accessToken;

    public GrpcMembershipEnableFenceGateway(
            UserSessionRevocationServiceGrpc.UserSessionRevocationServiceBlockingStub client,
            Supplier<String> accessToken) {
        this.client = client; this.accessToken = accessToken;
    }

    @Override public void release(UUID releaseId, UUID revocationId, UUID tenantId, UUID membershipId) {
        Metadata headers = new Metadata();
        headers.put(AUTHORIZATION, "Bearer " + accessToken.get());
        try {
            client.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
                    .releaseUserSessionFence(ReleaseUserSessionFenceRequest.newBuilder()
                            .setReleaseRequestId(releaseId.toString())
                            .setRevocationRequestId(revocationId.toString())
                            .setMembershipTarget(MembershipTarget.newBuilder()
                                    .setTenantId(tenantId.toString())
                                    .setMembershipId(membershipId.toString()).build()).build());
        } catch (StatusRuntimeException failure) {
            if (failure.getStatus().getCode() == Status.Code.FAILED_PRECONDITION)
                throw new SessionRevocationRejectedException();
            throw new SessionRevocationUnavailableException(failure);
        }
    }
}
