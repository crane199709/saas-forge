package io.saas.forge.entitlement.infrastructure.grpc;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.saas.forge.contracts.entitlement.quota.v1.QuotaCommandRequest;
import io.saas.forge.contracts.entitlement.quota.v1.QuotaCommandResponse;
import io.saas.forge.contracts.entitlement.quota.v1.QuotaCommandServiceGrpc;
import io.saas.forge.contracts.entitlement.quota.v1.QuotaPurpose;
import io.saas.forge.entitlement.application.quota.QuotaCommandApplicationService;
import io.saas.forge.entitlement.application.quota.QuotaCommandException;
import io.saas.forge.entitlement.application.quota.QuotaCommandResult;
import io.saas.forge.entitlement.application.quota.QuotaOperationIdReusedException;
import io.saas.forge.entitlement.domain.quota.QuotaOperationPurpose;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public final class QuotaCommandGrpcService extends QuotaCommandServiceGrpc.QuotaCommandServiceImplBase {
    private final QuotaCommandApplicationService commands;

    public QuotaCommandGrpcService(QuotaCommandApplicationService commands) {
        this.commands = commands;
    }

    @Override
    public void consume(
            QuotaCommandRequest request,
            StreamObserver<QuotaCommandResponse> responseObserver) {
        execute(request, commands::consume, responseObserver);
    }

    @Override
    public void release(
            QuotaCommandRequest request,
            StreamObserver<QuotaCommandResponse> responseObserver) {
        execute(request, commands::release, responseObserver);
    }

    private void execute(
            QuotaCommandRequest request,
            Command command,
            StreamObserver<QuotaCommandResponse> responseObserver) {
        UUID callerClientId = QuotaCommandServerInterceptor.callerClientId();
        if (callerClientId == null) {
            responseObserver.onError(Status.UNAUTHENTICATED.asRuntimeException());
            return;
        }
        try {
            QuotaCommandResult result = command.execute(
                    callerClientId,
                    canonicalUuidV7(request.getTenantId()),
                    request.getQuotaCode(),
                    request.getAmount(),
                    canonicalUuidV7(request.getOperationId()),
                    purpose(request.getPurpose()));
            responseObserver.onNext(QuotaCommandResponse.newBuilder()
                    .setUsage(result.usage())
                    .setLimit(result.limit())
                    .setReplayed(result.replayed())
                    .build());
            responseObserver.onCompleted();
        } catch (QuotaOperationIdReusedException exception) {
            responseObserver.onError(Status.ALREADY_EXISTS
                    .withDescription("QUOTA_OPERATION_ID_REUSED").asRuntimeException());
        } catch (QuotaCommandException exception) {
            responseObserver.onError(status(exception).asRuntimeException());
        } catch (IllegalArgumentException exception) {
            responseObserver.onError(Status.INVALID_ARGUMENT.asRuntimeException());
        } catch (RuntimeException exception) {
            responseObserver.onError(Status.INTERNAL.asRuntimeException());
        }
    }

    private static Status status(QuotaCommandException exception) {
        return switch (exception.outcome()) {
            case QUOTA_DEFINITION_NOT_FOUND -> Status.NOT_FOUND.withDescription(exception.outcome().name());
            case QUOTA_EXCEEDED -> Status.RESOURCE_EXHAUSTED.withDescription(exception.outcome().name());
            case SUBSCRIPTION_REQUIRED, QUOTA_RELEASE_UNDERFLOW ->
                    Status.FAILED_PRECONDITION.withDescription(exception.outcome().name());
            case SUCCESS -> Status.INTERNAL;
        };
    }

    private static QuotaOperationPurpose purpose(QuotaPurpose purpose) {
        return switch (purpose) {
            case TENANT_ADMIN_INITIALIZATION -> QuotaOperationPurpose.TENANT_ADMIN_INITIALIZATION;
            case INVITATION_ACCEPTANCE -> QuotaOperationPurpose.INVITATION_ACCEPTANCE;
            case MEMBERSHIP_DISABLE -> QuotaOperationPurpose.MEMBERSHIP_DISABLE;
            case MEMBERSHIP_ENABLE -> QuotaOperationPurpose.MEMBERSHIP_ENABLE;
            default -> throw new IllegalArgumentException("Quota purpose 不允许");
        };
    }

    private static UUID canonicalUuidV7(String value) {
        UUID id = UUID.fromString(value);
        if (id.version() != 7 || !id.toString().equals(value)) {
            throw new IllegalArgumentException("ID 必须是 canonical UUIDv7");
        }
        return id;
    }

    @FunctionalInterface
    private interface Command {
        QuotaCommandResult execute(
                UUID callerClientId,
                UUID tenantId,
                String quotaCode,
                int amount,
                UUID operationId,
                QuotaOperationPurpose purpose);
    }
}
