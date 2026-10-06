package io.saas.forge.tenantaccess.infrastructure.grpc;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import io.saas.forge.contracts.tenantaccess.membership.v1.MembershipValidationServiceGrpc;
import io.saas.forge.sdk.auth.ServiceAccessTokenAuthorizer;
import io.saas.forge.sdk.auth.ServiceAccessTokenInvalidException;
import io.saas.forge.sdk.auth.ServiceAccessTokenScopeException;
import io.saas.forge.tenantaccess.infrastructure.security.IamServiceClientId;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MembershipValidationServerInterceptorTest {
    private static final UUID IAM_CLIENT_ID =
            UUID.fromString("019535d9-0000-7000-8000-000000000001");
    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    private ServiceAccessTokenAuthorizer tokens;
    private MembershipValidationServerInterceptor interceptor;
    private ServerCall<String, String> call;
    private ServerCallHandler<String, String> next;
    private ServerCall.Listener<String> listener;
    private Metadata headers;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        tokens = mock(ServiceAccessTokenAuthorizer.class);
        interceptor = new MembershipValidationServerInterceptor(tokens, new io.saas.forge.tenantaccess.infrastructure.security.MembershipValidationClients(java.util.Set.of(IAM_CLIENT_ID)));
        call = mock(ServerCall.class);
        next = mock(ServerCallHandler.class);
        listener = mock(ServerCall.Listener.class);
        headers = new Metadata();
    }

    @Test
    void ignoresCallsForOtherGrpcServices() {
        method("other.Service");
        when(next.startCall(call, headers)).thenReturn(listener);

        assertSame(listener, interceptor.interceptCall(call, headers, next));

        verify(tokens, never()).authorize(any(), any(), any());
    }

    @Test
    void acceptsOnlyIamClientWithExactMembershipReadScope() {
        method(MembershipValidationServiceGrpc.SERVICE_NAME);
        headers.put(AUTHORIZATION, "Bearer iam-token");
        when(tokens.authorize("iam-token", "tenant-access:membership:read")).thenReturn(new io.saas.forge.sdk.auth.ServiceAccessAuthorization(IAM_CLIENT_ID,java.util.Set.of("tenant-access:membership:read")));
        when(next.startCall(call, headers)).thenReturn(listener);

        assertSame(listener, interceptor.interceptCall(call, headers, next));

        verify(tokens).authorize(
                "iam-token", "tenant-access:membership:read");
        verify(call, never()).close(any(), any());
    }

    @Test
    void acceptsConfiguredRemoteAndRejectsOtherClientsWithTheSameScope() {
        UUID remote = UUID.fromString("019535d9-0000-7000-8000-000000000002");
        interceptor = new MembershipValidationServerInterceptor(tokens,
                new io.saas.forge.tenantaccess.infrastructure.security.MembershipValidationClients(
                        java.util.Set.of(IAM_CLIENT_ID,remote)));
        method(MembershipValidationServiceGrpc.SERVICE_NAME);
        headers.put(AUTHORIZATION,"Bearer remote-token");
        when(next.startCall(call,headers)).thenReturn(listener);
        when(tokens.authorize("remote-token","tenant-access:membership:read"))
                .thenReturn(new io.saas.forge.sdk.auth.ServiceAccessAuthorization(remote,java.util.Set.of("tenant-access:membership:read")));
        assertSame(listener,interceptor.interceptCall(call,headers,next));
        when(tokens.authorize("remote-token","tenant-access:membership:read"))
                .thenReturn(new io.saas.forge.sdk.auth.ServiceAccessAuthorization(UUID.randomUUID(),java.util.Set.of("tenant-access:membership:read")));
        interceptor.interceptCall(call,headers,next);
        verifyClosed(Status.Code.UNAUTHENTICATED);
        verify(next).startCall(call,headers);
    }

    @Test
    void rejectsMissingBearerToken() {
        method(MembershipValidationServiceGrpc.SERVICE_NAME);

        interceptor.interceptCall(call, headers, next);

        verifyClosed(Status.Code.UNAUTHENTICATED);
        verify(next, never()).startCall(call, headers);
    }

    @Test
    void rejectsWrongScope() {
        method(MembershipValidationServiceGrpc.SERVICE_NAME);
        headers.put(AUTHORIZATION, "Bearer wrong-scope-token");
        doThrow(new ServiceAccessTokenScopeException())
                .when(tokens).authorize(
                        "wrong-scope-token", "tenant-access:membership:read");

        interceptor.interceptCall(call, headers, next);

        verifyClosed(Status.Code.PERMISSION_DENIED);
        verify(next, never()).startCall(call, headers);
    }

    @Test
    void rejectsInvalidToken() {
        method(MembershipValidationServiceGrpc.SERVICE_NAME);
        headers.put(AUTHORIZATION, "Bearer invalid-token");
        doThrow(new ServiceAccessTokenInvalidException())
                .when(tokens).authorize(
                        "invalid-token", "tenant-access:membership:read");

        interceptor.interceptCall(call, headers, next);

        verifyClosed(Status.Code.UNAUTHENTICATED);
        verify(next, never()).startCall(call, headers);
    }

    @Test
    void failsClosedWhenRevocationStatusIsUnavailable() {
        method(MembershipValidationServiceGrpc.SERVICE_NAME);
        headers.put(AUTHORIZATION, "Bearer unavailable-token");
        doThrow(new IllegalStateException("revocation unavailable"))
                .when(tokens).authorize(
                        "unavailable-token", "tenant-access:membership:read");

        interceptor.interceptCall(call, headers, next);

        verifyClosed(Status.Code.UNAVAILABLE);
        verify(next, never()).startCall(call, headers);
    }

    @SuppressWarnings("unchecked")
    private void method(String serviceName) {
        MethodDescriptor<String, String> method = mock(MethodDescriptor.class);
        when(method.getServiceName()).thenReturn(serviceName);
        when(call.getMethodDescriptor()).thenReturn(method);
    }

    private void verifyClosed(Status.Code code) {
        verify(call).close(argThat(status -> status.getCode() == code), any());
    }
}
