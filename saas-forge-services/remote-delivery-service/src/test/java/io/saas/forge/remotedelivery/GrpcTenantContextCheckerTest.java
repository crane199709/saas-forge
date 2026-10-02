package io.saas.forge.remotedelivery;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.saas.forge.contracts.tenantaccess.membership.v1.*;
import io.saas.forge.sdk.tenant.TenantContextSnapshot;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GrpcTenantContextCheckerTest {
    @Test
    void neverCachesAllowAndRefusesDenialMismatchedTenantAndUnavailableAuthority() {
        var client = mock(MembershipValidationServiceGrpc.MembershipValidationServiceBlockingStub.class);
        when(client.withInterceptors(any(io.grpc.ClientInterceptor.class))).thenReturn(client);
        var id = UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4c8f");
        var context = new TenantContextSnapshot(id, id, id);
        var checker = new GrpcTenantContextChecker(client, () -> "diagnostic-service-token");
        when(client.validateMembership(any())).thenReturn(ValidateMembershipResponse.newBuilder()
                .setValidatedMembership(ValidatedMembership.newBuilder()
                        .setMembershipId(id.toString()).setTenantId(id.toString())).build());
        checker.requireUsable(context);
        checker.requireUsable(context);
        verify(client, times(2)).validateMembership(ValidateMembershipRequest.newBuilder()
                .setIdentityId(id.toString()).setMembershipId(id.toString()).build());
        when(client.validateMembership(any())).thenReturn(ValidateMembershipResponse.newBuilder()
                .setMembershipNotUsable(MembershipNotUsable.getDefaultInstance()).build());
        assertEquals(403, assertThrows(ManifestException.class, () -> checker.requireUsable(context)).status());
        when(client.validateMembership(any())).thenReturn(ValidateMembershipResponse.newBuilder()
                .setValidatedMembership(ValidatedMembership.newBuilder()
                        .setMembershipId(id.toString()).setTenantId(UUID.randomUUID().toString())).build());
        assertEquals(503, assertThrows(ManifestException.class, () -> checker.requireUsable(context)).status());
        when(client.validateMembership(any())).thenThrow(new IllegalStateException("unavailable"));
        assertEquals(503, assertThrows(ManifestException.class, () -> checker.requireUsable(context)).status());
    }
}
