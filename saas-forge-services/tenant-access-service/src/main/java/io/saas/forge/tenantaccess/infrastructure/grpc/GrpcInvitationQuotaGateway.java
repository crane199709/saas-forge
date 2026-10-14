package io.saas.forge.tenantaccess.infrastructure.grpc;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import io.saas.forge.contracts.entitlement.quota.v1.QuotaCommandRequest;
import io.saas.forge.contracts.entitlement.quota.v1.QuotaCommandServiceGrpc;
import io.saas.forge.contracts.entitlement.quota.v1.QuotaPurpose;
import io.saas.forge.tenantaccess.application.administrator.QuotaUnavailableException;
import io.saas.forge.tenantaccess.application.administrator.RemoteWorkflowUnavailableException;
import io.saas.forge.tenantaccess.application.invitation.InvitationQuotaGateway;
import java.util.UUID;
import java.util.function.Supplier;

public final class GrpcInvitationQuotaGateway implements InvitationQuotaGateway {
    private final QuotaCommandServiceGrpc.QuotaCommandServiceBlockingStub client;
    private final Supplier<String> accessToken;

    public GrpcInvitationQuotaGateway(QuotaCommandServiceGrpc.QuotaCommandServiceBlockingStub client,
                                      Supplier<String> accessToken) {
        this.client = client; this.accessToken = accessToken;
    }

    @Override public void consume(UUID tenantId, UUID operationId) { execute(tenantId, operationId, true); }
    @Override public void release(UUID tenantId, UUID operationId) { execute(tenantId, operationId, false); }

    private void execute(UUID tenantId, UUID operationId, boolean consume) {
        try {
            var request = QuotaCommandRequest.newBuilder().setTenantId(tenantId.toString())
                    .setQuotaCode("max_users").setAmount(1).setOperationId(operationId.toString())
                    .setPurpose(QuotaPurpose.INVITATION_ACCEPTANCE).build();
            var authorized = client.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(
                    GrpcIdentityProvisioningGateway.metadata(accessToken.get())));
            if (consume) authorized.consume(request);
            else authorized.release(request);
        } catch (StatusRuntimeException failure) {
            String code = failure.getStatus().getDescription();
            if (consume && ((failure.getStatus().getCode() == Status.Code.RESOURCE_EXHAUSTED
                    && "QUOTA_EXCEEDED".equals(code))
                    || (failure.getStatus().getCode() == Status.Code.FAILED_PRECONDITION
                    && "SUBSCRIPTION_REQUIRED".equals(code)))) {
                throw new QuotaUnavailableException(code, failure);
            }
            throw new RemoteWorkflowUnavailableException(failure);
        } catch (RuntimeException failure) {
            throw new RemoteWorkflowUnavailableException(failure);
        }
    }
}
