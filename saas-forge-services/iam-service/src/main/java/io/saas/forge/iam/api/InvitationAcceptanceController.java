package io.saas.forge.iam.api;

import io.saas.forge.iam.application.authentication.ConsoleSessionException;
import io.saas.forge.iam.application.authentication.ConsoleSessionService;
import io.saas.forge.iam.application.authentication.InvitationAcceptanceGateway;
import io.saas.forge.iam.console.contract.api.InvitationAcceptanceApi;
import io.saas.forge.iam.console.contract.model.InvitationAcceptanceRequest;
import io.saas.forge.iam.console.contract.model.InvitationAcceptanceResult;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@RestController
@ConditionalOnProperty(name = "security.browser.console-enabled", havingValue = "true")
public class InvitationAcceptanceController implements InvitationAcceptanceApi {
    private final ConsoleSessionService sessions;
    private final InvitationAcceptanceGateway acceptance;

    public InvitationAcceptanceController(ConsoleSessionService sessions, InvitationAcceptanceGateway acceptance) {
        this.sessions = sessions; this.acceptance = acceptance;
    }

    @Override
    public ResponseEntity<InvitationAcceptanceResult> acceptInvitation(String csrf, String revision,
            UUID key, InvitationAcceptanceRequest body) {
        if (body == null || body.getToken() == null || !body.getToken().matches("[A-Za-z0-9_-]{43}")
                || key.version() != 7)
            throw new ConsoleSessionException(ConsoleSessionException.Code.VALIDATION_FAILED);
        HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes())
                .getRequest();
        UUID identityId = sessions.invitationIdentity(
                cookie(request, ConsoleAuthenticationController.SLOT_COOKIE),
                cookie(request, ConsoleAuthenticationController.REFRESH_COOKIE), revision);
        var result = acceptance.accept(body.getToken(), identityId, key,
                AuthenticationExceptionHandler.traceId(request));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new InvitationAcceptanceResult(result.membershipId(), result.tenantId(), result.replayed()));
    }

    private static String cookie(HttpServletRequest request, String name) {
        if (request.getCookies() == null) return null;
        var values = Arrays.stream(request.getCookies()).filter(item -> name.equals(item.getName())).toList();
        if (values.size() > 1) throw new ConsoleSessionException(ConsoleSessionException.Code.VALIDATION_FAILED);
        return values.isEmpty() ? null : values.get(0).getValue();
    }
}
