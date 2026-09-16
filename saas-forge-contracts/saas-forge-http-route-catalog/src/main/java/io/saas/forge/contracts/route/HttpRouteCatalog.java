package io.saas.forge.contracts.route;

import java.util.List;

/**
 * 构建期从正式 OpenAPI 与受控 Registry 生成的不可变公开路由契约。
 */
public record HttpRouteCatalog(int schemaVersion, List<Route> routes) {

    public HttpRouteCatalog {
        routes = routes == null ? null : List.copyOf(routes);
    }

    public record Route(
            String operationId,
            HttpMethod method,
            String path,
            String serviceId,
            CredentialRequirement credentialRequirement,
            List<String> requiredScopes) {

        public Route {
            requiredScopes = requiredScopes == null ? null : List.copyOf(requiredScopes);
        }
    }

    public enum HttpMethod {
        GET,
        POST,
        PUT,
        PATCH,
        DELETE,
        HEAD,
        OPTIONS,
        TRACE
    }

    public enum CredentialRequirement {
        ANONYMOUS,
        BROWSER_SESSION_SLOT_REQUIRED,
        CONSOLE_SLOT_REQUIRED,
        CONSOLE_SESSION_REQUIRED,
        PLATFORM_REFRESH_COOKIE_REQUIRED,
        TENANT_REFRESH_COOKIE_REQUIRED,
        OAUTH_CLIENT_BASIC_REQUIRED,
        USER_OPTIONAL,
        USER_REQUIRED,
        SERVICE_REQUIRED
    }
}
