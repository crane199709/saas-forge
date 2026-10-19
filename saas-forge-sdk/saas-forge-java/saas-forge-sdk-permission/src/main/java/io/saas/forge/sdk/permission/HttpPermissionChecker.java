package io.saas.forge.sdk.permission;

import io.saas.forge.sdk.core.rest.ApiClient;
import io.saas.forge.sdk.core.rest.ApiException;
import io.saas.forge.sdk.core.rest.api.PermissionsApi;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

/** 每次通过正式 Permission operation 回源；不保存 allow 决策。 */
public final class HttpPermissionChecker implements PermissionChecker {
    private final Supplier<URI> authorityEndpoint;

    public HttpPermissionChecker(Supplier<URI> authorityEndpoint) {
        this.authorityEndpoint = Objects.requireNonNull(authorityEndpoint);
    }

    @Override
    public void require(String code, String authorization) {
        if (code == null || !code.matches("^[a-z][a-z0-9-]*:[a-z][a-z0-9-]*:[a-z][a-z0-9-]*$")
                || authorization == null || !authorization.startsWith("Bearer ")) {
            throw new PermissionDeniedException();
        }
        try {
            URI endpoint = Objects.requireNonNull(authorityEndpoint.get());
            ApiClient client = new ApiClient().setConnectTimeout(Duration.ofSeconds(2))
                    .setReadTimeout(Duration.ofSeconds(3))
                    .setRequestInterceptor(request -> request.header("Authorization", authorization));
            client.updateBaseUri(endpoint.toString());
            var decision = new PermissionsApi(client).decidePermission(code);
            if (decision == null || !code.equals(decision.getCode()) || decision.getAllowed() == null) {
                throw new PermissionAuthorityUnavailableException(null);
            }
            if (!decision.getAllowed()) {
                throw new PermissionDeniedException();
            }
        } catch (ApiException failure) {
            if (failure.getCode() == 401 || failure.getCode() == 403) {
                throw new PermissionDeniedException();
            }
            throw new PermissionAuthorityUnavailableException(failure);
        } catch (PermissionDeniedException | PermissionAuthorityUnavailableException expected) {
            throw expected;
        } catch (RuntimeException failure) {
            throw new PermissionAuthorityUnavailableException(failure);
        }
    }
}
