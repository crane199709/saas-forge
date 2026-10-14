package io.saas.forge.tenantaccess.infrastructure.grpc;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.saas.forge.contracts.tenantaccess.invitation.v1.InvitationAcceptanceServiceGrpc;
import io.saas.forge.sdk.auth.ServiceAccessTokenAuthorizer;
import io.saas.forge.sdk.auth.ServiceAccessTokenInvalidException;
import io.saas.forge.sdk.auth.ServiceAccessTokenScopeException;
import io.saas.forge.tenantaccess.infrastructure.security.IamServiceClientId;
import org.springframework.grpc.server.GlobalServerInterceptor;
import org.springframework.stereotype.Component;

/** 仅 IAM 的保留服务身份可提交已验证浏览器会话的 Identity。 */
@Component
@GlobalServerInterceptor
public final class InvitationAcceptanceServerInterceptor implements ServerInterceptor {
    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
    private final ServiceAccessTokenAuthorizer tokens;
    private final IamServiceClientId iam;

    public InvitationAcceptanceServerInterceptor(ServiceAccessTokenAuthorizer tokens, IamServiceClientId iam) {
        this.tokens = tokens; this.iam = iam;
    }

    @Override public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
            Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        if (!InvitationAcceptanceServiceGrpc.SERVICE_NAME.equals(call.getMethodDescriptor().getServiceName()))
            return next.startCall(call, headers);
        String authorization = headers.get(AUTHORIZATION);
        if (authorization == null || !authorization.startsWith("Bearer "))
            return close(call, Status.UNAUTHENTICATED);
        try {
            var authorized = tokens.authorize(authorization.substring(7), "tenant-access:invitation:accept");
            if (!iam.value().equals(authorized.clientId())) return close(call, Status.UNAUTHENTICATED);
            return next.startCall(call, headers);
        } catch (ServiceAccessTokenScopeException forbidden) {
            return close(call, Status.PERMISSION_DENIED);
        } catch (ServiceAccessTokenInvalidException invalid) {
            return close(call, Status.UNAUTHENTICATED);
        } catch (RuntimeException unavailable) {
            return close(call, Status.UNAVAILABLE);
        }
    }

    private static <ReqT, RespT> ServerCall.Listener<ReqT> close(ServerCall<ReqT, RespT> call, Status status) {
        call.close(status, new Metadata());
        return new ServerCall.Listener<ReqT>() {};
    }
}
