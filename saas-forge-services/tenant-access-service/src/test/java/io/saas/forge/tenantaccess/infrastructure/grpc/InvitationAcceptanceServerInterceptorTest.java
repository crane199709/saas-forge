package io.saas.forge.tenantaccess.infrastructure.grpc;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import io.saas.forge.contracts.tenantaccess.invitation.v1.InvitationAcceptanceServiceGrpc;
import io.saas.forge.sdk.auth.ServiceAccessAuthorization;
import io.saas.forge.sdk.auth.ServiceAccessTokenAuthorizer;
import io.saas.forge.sdk.auth.ServiceAccessTokenInvalidException;
import io.saas.forge.sdk.auth.ServiceAccessTokenScopeException;
import io.saas.forge.tenantaccess.infrastructure.security.IamServiceClientId;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InvitationAcceptanceServerInterceptorTest {
    private static final UUID IAM = UUID.fromString("01991b28-7c00-7000-8000-000000000001");
    private static final UUID OTHER = UUID.fromString("01991b28-7c00-7000-8000-000000000002");
    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    private ServiceAccessTokenAuthorizer tokens;
    private InvitationAcceptanceServerInterceptor interceptor;
    private ServerCall<String, String> call;
    private ServerCallHandler<String, String> next;
    private ServerCall.Listener<String> listener;
    private Metadata headers;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        tokens = mock(ServiceAccessTokenAuthorizer.class);
        interceptor = new InvitationAcceptanceServerInterceptor(tokens, new IamServiceClientId(IAM));
        call = mock(ServerCall.class);
        next = mock(ServerCallHandler.class);
        listener = mock(ServerCall.Listener.class);
        headers = new Metadata();
    }

    @Test
    void leavesOtherServicesUntouched() {
        method("other.Service");
        when(next.startCall(call, headers)).thenReturn(listener);
        assertSame(listener, interceptor.interceptCall(call, headers, next));
        verify(tokens, never()).authorize(any(), any());
    }

    @Test
    void permitsOnlyReservedIamIdentityWithInvitationAcceptanceScope() {
        method(InvitationAcceptanceServiceGrpc.SERVICE_NAME);
        headers.put(AUTHORIZATION, "Bearer iam-token");
        when(tokens.authorize("iam-token", "tenant-access:invitation:accept"))
                .thenReturn(new ServiceAccessAuthorization(IAM, Set.of("tenant-access:invitation:accept")));
        when(next.startCall(call, headers)).thenReturn(listener);
        assertSame(listener, interceptor.interceptCall(call, headers, next));
        verify(call, never()).close(any(), any());

        when(tokens.authorize("iam-token", "tenant-access:invitation:accept"))
                .thenReturn(new ServiceAccessAuthorization(OTHER, Set.of("tenant-access:invitation:accept")));
        interceptor.interceptCall(call, headers, next);
        closed(Status.Code.UNAUTHENTICATED);
    }

    @Test
    void rejectsMissingBearerOrInvalidTokenWithoutCallingBusinessService() {
        method(InvitationAcceptanceServiceGrpc.SERVICE_NAME);
        interceptor.interceptCall(call, headers, next);
        closed(Status.Code.UNAUTHENTICATED);
        verify(next, never()).startCall(call, headers);
    }

    @Test
    void distinguishesScopeDenialFromInvalidAndUnavailableAuthorization() {
        method(InvitationAcceptanceServiceGrpc.SERVICE_NAME);
        headers.put(AUTHORIZATION, "Bearer service-token");
        doThrow(new ServiceAccessTokenScopeException())
                .when(tokens).authorize("service-token", "tenant-access:invitation:accept");
        interceptor.interceptCall(call, headers, next);
        closed(Status.Code.PERMISSION_DENIED);

        reset(call);
        method(InvitationAcceptanceServiceGrpc.SERVICE_NAME);
        doThrow(new ServiceAccessTokenInvalidException())
                .when(tokens).authorize("service-token", "tenant-access:invitation:accept");
        interceptor.interceptCall(call, headers, next);
        closed(Status.Code.UNAUTHENTICATED);

        reset(call);
        method(InvitationAcceptanceServiceGrpc.SERVICE_NAME);
        doThrow(new IllegalStateException("revocation source offline"))
                .when(tokens).authorize("service-token", "tenant-access:invitation:accept");
        interceptor.interceptCall(call, headers, next);
        closed(Status.Code.UNAVAILABLE);
        verify(next, never()).startCall(call, headers);
    }

    @SuppressWarnings("unchecked")
    private void method(String service) {
        MethodDescriptor<String, String> descriptor = mock(MethodDescriptor.class);
        when(descriptor.getServiceName()).thenReturn(service);
        when(call.getMethodDescriptor()).thenReturn(descriptor);
    }

    private void closed(Status.Code code) {
        verify(call).close(argThat(status -> status.getCode() == code), any());
    }
}
