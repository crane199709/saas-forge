package io.saas.forge.tenantaccess.infrastructure.grpc;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import io.saas.forge.contracts.iam.passwordsetup.v1.DeliverPasswordSetupRequest;
import io.saas.forge.contracts.iam.passwordsetup.v1.PasswordSetupDeliveryResult;
import io.saas.forge.contracts.iam.passwordsetup.v1.PasswordSetupServiceGrpc;
import io.saas.forge.tenantaccess.application.administrator.RemoteWorkflowUnavailableException;
import io.saas.forge.tenantaccess.application.invitation.InvitationPasswordSetupGateway;
import java.util.UUID;
import java.util.function.Supplier;

public final class GrpcInvitationPasswordSetupGateway implements InvitationPasswordSetupGateway {
    private final PasswordSetupServiceGrpc.PasswordSetupServiceBlockingStub client;
    private final Supplier<String> accessToken;

    public GrpcInvitationPasswordSetupGateway(
            PasswordSetupServiceGrpc.PasswordSetupServiceBlockingStub client, Supplier<String> accessToken) {
        this.client = client;
        this.accessToken = accessToken;
    }

    @Override
    public Result request(UUID requestId, UUID identityId) {
        try {
            PasswordSetupDeliveryResult result = client.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(
                            GrpcIdentityProvisioningGateway.metadata(accessToken.get())))
                    .deliverPasswordSetup(DeliverPasswordSetupRequest.newBuilder()
                            .setRequestId(requestId.toString()).setIdentityId(identityId.toString()).build())
                    .getResult();
            return switch (result) {
                case DELIVERED -> Result.MAIL_SERVICE_ACCEPTED;
                case PASSWORD_READY -> Result.PASSWORD_READY;
                default -> throw new RemoteWorkflowUnavailableException(
                        new IllegalStateException("IAM 返回未知 Password Setup 状态"));
            };
        } catch (StatusRuntimeException exception) {
            if (exception.getStatus().getCode() == Status.Code.FAILED_PRECONDITION)
                return Result.RECOVERY_REQUIRED;
            throw new RemoteWorkflowUnavailableException(exception);
        } catch (RemoteWorkflowUnavailableException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new RemoteWorkflowUnavailableException(exception);
        }
    }
}
