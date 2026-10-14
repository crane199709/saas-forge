package io.saas.forge.tenantaccess.infrastructure.grpc;

import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import io.saas.forge.contracts.iam.session.v1.MembershipTarget;
import io.saas.forge.contracts.iam.session.v1.RecoverUserSessionRevocationRequest;
import io.saas.forge.contracts.iam.session.v1.RevokeUserSessionsRequest;
import io.saas.forge.contracts.iam.session.v1.UserSessionRevocationServiceGrpc;
import io.saas.forge.tenantaccess.application.membership.MembershipRevocationGateway;
import io.saas.forge.tenantaccess.application.tenant.SessionRevocationGateway.Result;
import io.saas.forge.tenantaccess.application.tenant.SessionRevocationRejectedException;
import io.saas.forge.tenantaccess.application.tenant.SessionRevocationUnavailableException;
import java.util.UUID;
import java.util.function.Supplier;

public final class GrpcMembershipRevocationGateway implements MembershipRevocationGateway {
    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
    private final UserSessionRevocationServiceGrpc.UserSessionRevocationServiceBlockingStub client;
    private final Supplier<String> accessToken;

    public GrpcMembershipRevocationGateway(
            UserSessionRevocationServiceGrpc.UserSessionRevocationServiceBlockingStub client,
            Supplier<String> accessToken) {
        this.client = client;
        this.accessToken = accessToken;
    }

    @Override public Result revoke(UUID requestId, UUID tenantId, UUID membershipId) {
        try {
            var response = authorized().revokeUserSessions(RevokeUserSessionsRequest.newBuilder()
                    .setRequestId(requestId.toString()).setMembershipTarget(target(tenantId, membershipId)).build());
            return switch (response.getOutcomeCase()) {
                case PENDING -> Result.pending(response.getPending().getRetryAfterSeconds());
                case COMPLETED -> Result.completed(response.getCompleted().getRevokedFamilyCount(),
                        response.getCompleted().getRevokedJtiCount());
                case OUTCOME_NOT_SET -> throw new IllegalStateException("IAM 未返回撤销结果");
            };
        } catch (StatusRuntimeException failure) {
            throw map(failure);
        }
    }

    @Override public void recover(UUID requestId, UUID tenantId, UUID membershipId) {
        try {
            authorized().recoverUserSessionRevocation(RecoverUserSessionRevocationRequest.newBuilder()
                    .setRevocationRequestId(requestId.toString())
                    .setMembershipTarget(target(tenantId, membershipId)).build());
        } catch (StatusRuntimeException failure) {
            throw map(failure);
        }
    }

    private UserSessionRevocationServiceGrpc.UserSessionRevocationServiceBlockingStub authorized() {
        Metadata headers = new Metadata();
        headers.put(AUTHORIZATION, "Bearer " + accessToken.get());
        return client.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
    }

    private static MembershipTarget target(UUID tenantId, UUID membershipId) {
        return MembershipTarget.newBuilder().setTenantId(tenantId.toString())
                .setMembershipId(membershipId.toString()).build();
    }

    private static RuntimeException map(StatusRuntimeException failure) {
        return switch (failure.getStatus().getCode()) {
            case UNAVAILABLE, DEADLINE_EXCEEDED -> new SessionRevocationUnavailableException(failure);
            case FAILED_PRECONDITION -> new SessionRevocationRejectedException();
            default -> new IllegalStateException("IAM Membership 撤销失败: " + failure.getStatus().getCode(), failure);
        };
    }
}
