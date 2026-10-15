package io.saas.forge.iam.api;

import io.saas.forge.iam.application.authorization.PlatformAdminGrantService;
import io.saas.forge.iam.application.authorization.PlatformAdminQueries;
import io.saas.forge.iam.application.authorization.PlatformAdminRevokeService;
import io.saas.forge.iam.application.client.OAuthClientManagementAuthorizer;
import io.saas.forge.iam.contract.api.PlatformAdministratorsApi;
import io.saas.forge.iam.contract.model.PlatformAdminGrantRequest;
import io.saas.forge.iam.contract.model.PlatformAdminGrantResult;
import io.saas.forge.iam.contract.model.PlatformAdministrator;
import io.saas.forge.iam.contract.model.PlatformAdministratorPage;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@RestController
public class PlatformAdministratorsController implements PlatformAdministratorsApi {
    private final OAuthClientManagementAuthorizer authorizer;
    private final PlatformAdminGrantService grants;
    private final PlatformAdminQueries queries;
    private final PlatformAdminRevokeService revokes;

    public PlatformAdministratorsController(OAuthClientManagementAuthorizer authorizer,
            PlatformAdminGrantService grants, PlatformAdminQueries queries,
            PlatformAdminRevokeService revokes) {
        this.authorizer = authorizer;
        this.grants = grants;
        this.queries = queries;
        this.revokes = revokes;
    }

    @Override
    public ResponseEntity<PlatformAdministratorPage> listPlatformAdministrators() {
        authorizer.authorize(currentRequest().getHeader(HttpHeaders.AUTHORIZATION));
        var items = queries.list().stream().map(item -> new PlatformAdministrator(
                item.identityId(), item.roleAssignmentId(), item.email(),
                item.passwordReady(), OffsetDateTime.ofInstant(item.assignedAt(), ZoneOffset.UTC))).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new PlatformAdministratorPage(items));
    }

    @Override
    public ResponseEntity<PlatformAdminGrantResult> grantPlatformAdministrator(
            UUID requestId, PlatformAdminGrantRequest body) {
        HttpServletRequest request = currentRequest();
        UUID actor = authorizer.authorize(request.getHeader(HttpHeaders.AUTHORIZATION));
        var result = grants.grant(actor, requestId, body.getEmail(), OAuthClientsController.traceId(request));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new PlatformAdminGrantResult(
                result.identityId(), result.roleAssignmentId(), result.newlyGranted(),
                PlatformAdminGrantResult.SetupStateEnum.valueOf(result.setupState().name())));
    }

    @Override
    public ResponseEntity<Void> revokePlatformAdministrator(UUID identityId, UUID requestId) {
        HttpServletRequest request = currentRequest();
        UUID actor = authorizer.authorize(request.getHeader(HttpHeaders.AUTHORIZATION));
        revokes.revoke(actor, identityId, requestId, OAuthClientsController.traceId(request));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private static HttpServletRequest currentRequest() {
        return ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
    }
}
