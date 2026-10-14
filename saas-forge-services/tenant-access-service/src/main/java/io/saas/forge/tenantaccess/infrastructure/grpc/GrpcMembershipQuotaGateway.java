package io.saas.forge.tenantaccess.infrastructure.grpc;

import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import io.saas.forge.contracts.entitlement.quota.v1.QuotaCommandRequest;
import io.saas.forge.contracts.entitlement.quota.v1.QuotaCommandServiceGrpc;
import io.saas.forge.contracts.entitlement.quota.v1.QuotaPurpose;
import io.saas.forge.tenantaccess.application.administrator.RemoteWorkflowUnavailableException;
import io.saas.forge.tenantaccess.application.membership.MembershipQuotaGateway;
import java.util.UUID;
import java.util.function.Supplier;

public final class GrpcMembershipQuotaGateway implements MembershipQuotaGateway {
    private final QuotaCommandServiceGrpc.QuotaCommandServiceBlockingStub client;
    private final Supplier<String> accessToken;

    public GrpcMembershipQuotaGateway(QuotaCommandServiceGrpc.QuotaCommandServiceBlockingStub client,
            Supplier<String> accessToken) {
        this.client = client; this.accessToken = accessToken;
    }

    @Override public void release(UUID tenantId, UUID operationId) {
        try {
            client.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(
                    GrpcIdentityProvisioningGateway.metadata(accessToken.get())))
                    .release(QuotaCommandRequest.newBuilder().setTenantId(tenantId.toString())
                            .setQuotaCode("max_users").setAmount(1).setOperationId(operationId.toString())
                            .setPurpose(QuotaPurpose.MEMBERSHIP_DISABLE).build());
        } catch (StatusRuntimeException failure) {
            throw new RemoteWorkflowUnavailableException(failure);
        }
    }
}
