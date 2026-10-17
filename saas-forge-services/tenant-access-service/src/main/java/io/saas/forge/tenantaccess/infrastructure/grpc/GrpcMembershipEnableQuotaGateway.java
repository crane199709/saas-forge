package io.saas.forge.tenantaccess.infrastructure.grpc;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import io.saas.forge.contracts.entitlement.quota.v1.QuotaCommandRequest;
import io.saas.forge.contracts.entitlement.quota.v1.QuotaCommandServiceGrpc;
import io.saas.forge.contracts.entitlement.quota.v1.QuotaPurpose;
import io.saas.forge.tenantaccess.application.administrator.RemoteWorkflowUnavailableException;
import io.saas.forge.tenantaccess.application.membership.MembershipEnableQuotaGateway;
import io.saas.forge.tenantaccess.application.membership.MembershipQuotaExceededException;
import java.util.UUID;
import java.util.function.Supplier;

public final class GrpcMembershipEnableQuotaGateway implements MembershipEnableQuotaGateway {
    private final QuotaCommandServiceGrpc.QuotaCommandServiceBlockingStub client;
    private final Supplier<String> accessToken;

    public GrpcMembershipEnableQuotaGateway(QuotaCommandServiceGrpc.QuotaCommandServiceBlockingStub client,
            Supplier<String> accessToken) {
        this.client = client; this.accessToken = accessToken;
    }

    @Override public void consume(UUID tenantId, UUID operationId) {
        try {
            authorized().consume(request(tenantId, operationId));
        } catch (StatusRuntimeException failure) {
            if (failure.getStatus().getCode() == Status.Code.RESOURCE_EXHAUSTED)
                throw new MembershipQuotaExceededException(failure);
            throw new RemoteWorkflowUnavailableException(failure);
        }
    }

    @Override public void release(UUID tenantId, UUID operationId) {
        try {
            authorized().release(request(tenantId, operationId));
        } catch (StatusRuntimeException failure) {
            throw new RemoteWorkflowUnavailableException(failure);
        }
    }

    private QuotaCommandServiceGrpc.QuotaCommandServiceBlockingStub authorized() {
        return client.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(
                GrpcIdentityProvisioningGateway.metadata(accessToken.get())));
    }

    private static QuotaCommandRequest request(UUID tenantId, UUID operationId) {
        return QuotaCommandRequest.newBuilder().setTenantId(tenantId.toString())
                .setQuotaCode("max_users").setAmount(1).setOperationId(operationId.toString())
                .setPurpose(QuotaPurpose.MEMBERSHIP_ENABLE).build();
    }
}
