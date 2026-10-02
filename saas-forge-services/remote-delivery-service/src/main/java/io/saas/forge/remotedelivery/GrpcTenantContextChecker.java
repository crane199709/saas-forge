package io.saas.forge.remotedelivery;

import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import io.saas.forge.contracts.tenantaccess.membership.v1.MembershipValidationServiceGrpc;
import io.saas.forge.contracts.tenantaccess.membership.v1.ValidateMembershipRequest;
import io.saas.forge.sdk.tenant.TenantContextSnapshot;
import java.util.function.Supplier;

/** 实时读取 Tenant Access 对 Membership 与所属 Tenant 的联合可用性，不缓存允许结果。 */
final class GrpcTenantContextChecker {
    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
    private final MembershipValidationServiceGrpc.MembershipValidationServiceBlockingStub client;
    private final Supplier<String> token;

    GrpcTenantContextChecker(MembershipValidationServiceGrpc.MembershipValidationServiceBlockingStub client,
            Supplier<String> token) {
        this.client = client;
        this.token = token;
    }

    void requireUsable(TenantContextSnapshot context) {
        try {
            String accessToken = token.get();
            if (accessToken == null || accessToken.isBlank()) throw new IllegalStateException();
            var headers = new Metadata();
            headers.put(AUTHORIZATION, "Bearer " + accessToken);
            var response = client.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
                    .validateMembership(ValidateMembershipRequest.newBuilder()
                            .setIdentityId(context.identityId().toString())
                            .setMembershipId(context.membershipId().toString()).build());
            switch (response.getOutcomeCase()) {
                case MEMBERSHIP_NOT_USABLE -> throw new ManifestException(403, "TENANT_CONTEXT_NOT_USABLE");
                case VALIDATED_MEMBERSHIP -> {
                    var membership = response.getValidatedMembership();
                    if (!context.membershipId().toString().equals(membership.getMembershipId())
                            || !context.tenantId().toString().equals(membership.getTenantId()))
                        throw new IllegalStateException();
                }
                case OUTCOME_NOT_SET -> throw new IllegalStateException();
            }
        } catch (ManifestException refused) {
            throw refused;
        } catch (RuntimeException unavailable) {
            throw new ManifestException(503, "TENANT_AUTHORITY_UNAVAILABLE");
        }
    }
}
