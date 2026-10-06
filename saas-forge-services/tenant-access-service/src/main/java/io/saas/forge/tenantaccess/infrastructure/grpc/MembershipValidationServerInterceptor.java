package io.saas.forge.tenantaccess.infrastructure.grpc;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.saas.forge.contracts.tenantaccess.membership.v1.MembershipValidationServiceGrpc;
import io.saas.forge.sdk.auth.ServiceAccessTokenInvalidException;
import io.saas.forge.sdk.auth.ServiceAccessTokenScopeException;
import io.saas.forge.sdk.auth.ServiceAccessTokenAuthorizer;
import io.saas.forge.tenantaccess.infrastructure.security.MembershipValidationClients;
import org.springframework.grpc.server.GlobalServerInterceptor;
import org.springframework.stereotype.Component;

/** Membership Validation 只接受指定 Reserved Client 的 Membership 读取 Scope。 */
@GlobalServerInterceptor
@Component
public final class MembershipValidationServerInterceptor implements ServerInterceptor {
    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
    private static final String REQUIRED_SCOPE = "tenant-access:membership:read";

    private final ServiceAccessTokenAuthorizer tokens;
    private final MembershipValidationClients allowedClients;

    public MembershipValidationServerInterceptor(
            ServiceAccessTokenAuthorizer tokens,
            MembershipValidationClients allowedClients) {
        this.tokens = tokens;
        this.allowedClients = allowedClients;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {
        if (!MembershipValidationServiceGrpc.SERVICE_NAME.equals(
                call.getMethodDescriptor().getServiceName())) {
            return next.startCall(call, headers);
        }
        String authorization = headers.get(AUTHORIZATION);
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return close(call, Status.UNAUTHENTICATED);
        }
        try {
            var authorized = tokens.authorize(authorization.substring("Bearer ".length()), REQUIRED_SCOPE);
            if (!allowedClients.values().contains(authorized.clientId())) {
                return close(call, Status.UNAUTHENTICATED);
            }
            return next.startCall(call, headers);
        } catch (ServiceAccessTokenScopeException exception) {
            return close(call, Status.PERMISSION_DENIED);
        } catch (ServiceAccessTokenInvalidException exception) {
            return close(call, Status.UNAUTHENTICATED);
        } catch (RuntimeException exception) {
            return close(call, Status.UNAVAILABLE);
        }
    }

    private static <ReqT, RespT> ServerCall.Listener<ReqT> close(
            ServerCall<ReqT, RespT> call, Status status) {
        call.close(status, new Metadata());
        return new ServerCall.Listener<ReqT>() {};
    }
}
