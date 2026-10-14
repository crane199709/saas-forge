package io.saas.forge.iam.api;

import io.saas.forge.iam.application.authentication.ConsoleSessionException;
import io.saas.forge.iam.application.authentication.ConsoleSessionService;
import io.saas.forge.iam.application.authentication.InvitationAcceptanceGateway;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/auth/invitation-acceptances")
@ConditionalOnProperty(name = "security.browser.console-enabled", havingValue = "true")
public final class InvitationAcceptanceController {
    private final ConsoleSessionService sessions;
    private final InvitationAcceptanceGateway acceptance;

    public InvitationAcceptanceController(ConsoleSessionService sessions, InvitationAcceptanceGateway acceptance) {
        this.sessions = sessions; this.acceptance = acceptance;
    }

    @PostMapping
    public ResponseEntity<Result> accept(@RequestHeader("If-Match") String revision,
            @RequestHeader("Idempotency-Key") UUID key, @RequestBody Request body,
            HttpServletRequest request) {
        if (body == null || body.token() == null || !body.token().matches("[A-Za-z0-9_-]{43}")
                || key.version() != 7)
            throw new ConsoleSessionException(ConsoleSessionException.Code.VALIDATION_FAILED);
        UUID identityId = sessions.invitationIdentity(
                cookie(request, ConsoleAuthenticationController.SLOT_COOKIE),
                cookie(request, ConsoleAuthenticationController.REFRESH_COOKIE), revision);
        var result = acceptance.accept(body.token(), identityId, key,
                AuthenticationExceptionHandler.traceId(request));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new Result(result.membershipId(), result.tenantId(), result.replayed()));
    }

    private static String cookie(HttpServletRequest request, String name) {
        if (request.getCookies() == null) return null;
        var values = Arrays.stream(request.getCookies()).filter(item -> name.equals(item.getName())).toList();
        if (values.size() > 1) throw new ConsoleSessionException(ConsoleSessionException.Code.VALIDATION_FAILED);
        return values.isEmpty() ? null : values.get(0).getValue();
    }

    public record Request(String token) {}
    public record Result(UUID membershipId, UUID tenantId, boolean replayed) {}
}
