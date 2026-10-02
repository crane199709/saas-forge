package io.saas.forge.remotedelivery;

import io.grpc.Metadata;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import io.saas.forge.contracts.iam.authorization.v1.CheckPlatformRoleRequest;
import io.saas.forge.contracts.iam.authorization.v1.PlatformAuthorizationServiceGrpc;
import io.saas.forge.sdk.auth.PlatformRoleChecker;
import java.util.UUID;
import java.util.function.Supplier;

/** 使用 Remote Delivery 的短期 Service Access Token 调用 IAM Platform Role 权威校验。 */
public final class GrpcPlatformRoleChecker implements PlatformRoleChecker {
    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    private final PlatformAuthorizationServiceGrpc.PlatformAuthorizationServiceBlockingStub client;
    private final Supplier<String> serviceAccessToken;

    public GrpcPlatformRoleChecker(
            PlatformAuthorizationServiceGrpc.PlatformAuthorizationServiceBlockingStub client,
            Supplier<String> serviceAccessToken) {
        if (client == null || serviceAccessToken == null) {
            throw new IllegalArgumentException("IAM Platform Role 调用配置不能为空");
        }
        this.client = client;
        this.serviceAccessToken = serviceAccessToken;
    }

    @Override
    public boolean isAllowed(UUID identityId, String roleKey) {
        if (identityId == null || roleKey == null || roleKey.isBlank()) {
            throw new IllegalArgumentException("IAM Platform Role 请求字段不能为空");
        }
        String token = serviceAccessToken.get();
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("Service Access Token 不可用");
        }
        Metadata metadata = new Metadata();
        metadata.put(AUTHORIZATION, "Bearer " + token);
        try {
            return client.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata))
                    .checkPlatformRole(CheckPlatformRoleRequest.newBuilder()
                            .setIdentityId(identityId.toString())
                            .setRoleKey(roleKey)
                            .build())
                    .getAllowed();
        } catch (StatusRuntimeException exception) {
            throw new IllegalStateException("IAM Platform Role 校验不可用", exception);
        }
    }
}
