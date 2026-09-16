package io.saas.forge.iam.api;

import io.saas.forge.iam.application.authentication.ConsoleSessionService;
import io.saas.forge.iam.application.authentication.ConsoleSessionTermination;
import io.saas.forge.iam.application.authentication.ConsoleSessionException;
import io.saas.forge.iam.console.contract.api.ConsoleAuthenticationApi;
import io.saas.forge.iam.console.contract.model.*;
import io.saas.forge.iam.domain.session.RefreshTokenFamilyPurpose;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "security.browser.console-enabled", havingValue = "true")
public final class ConsoleAuthenticationController implements ConsoleAuthenticationApi {
    static final String SLOT_COOKIE = "__Host-sf_console_slot";
    static final String REFRESH_COOKIE = "__Host-sf_console_refresh";
    static final List<String> LEGACY_COOKIES = List.of("__Host-sf_platform_refresh", "__Host-sf_tenant_refresh", "__Host-sf_refresh");
    private final ConsoleSessionService sessions;
    private final ConsoleSessionTermination termination;

    public ConsoleAuthenticationController(ConsoleSessionService sessions, ConsoleSessionTermination termination) {
        this.sessions = sessions; this.termination = termination;
    }

    @Override
    public ResponseEntity<ConsoleBootstrapResult> bootstrapConsoleSession(String csrf, Object body) {
        requireEmpty(body);
        var result = sessions.bootstrap(cookie(SLOT_COOKIE), cookie(REFRESH_COOKIE),
                LEGACY_COOKIES.stream().map(this::cookie).filter(java.util.Objects::nonNull).toList());
        var slot = result.slot();
        var response = response(slot.revision());
        if (result.newLocator() != null) {
            response.header(HttpHeaders.SET_COOKIE, cookie(SLOT_COOKIE, result.newLocator(), 28800), cookie(REFRESH_COOKIE, "", 0));
        }
        for (String name : LEGACY_COOKIES) if (cookie(name) != null) response.header(HttpHeaders.SET_COOKIE, cookie(name, "", 0));
        return response.body(new ConsoleBootstrapResult(Long.toString(slot.revision()), slot.familyId() != null,
                ConsoleBootstrapResult.TransitionEnum.valueOf(slot.transition().name())));
    }

    @Override
    public ResponseEntity<ConsoleAuthenticationResult> loginConsoleSession(String csrf, String revision, ConsoleLoginRequest body) {
        return authentication(sessions.login(cookie(SLOT_COOKIE), revision, body.getEmail(), body.getPassword()));
    }

    @Override
    public ResponseEntity<ConsoleAuthenticationResult> refreshConsoleSession(String csrf, String revision, UUID key, Object body) {
        requireEmpty(body);
        var result = sessions.refresh(cookie(SLOT_COOKIE), cookie(REFRESH_COOKIE), revision, key);
        if (result.snapshot() == null) throw new ConsoleSessionException(ConsoleSessionException.Code.SESSION_INVALID);
        return authentication(result);
    }

    @Override
    public ResponseEntity<ConsoleSessionSnapshot> getConsoleSession() {
        var snapshot = snapshot();
        return response(Long.parseLong(snapshot.getRevision())).body(snapshot);
    }

    @Override
    public ResponseEntity<ConsoleAvailableContextsResult> getAvailableWorkContexts() {
        var snapshot = snapshot();
        if (snapshot.getState() == ConsoleSessionSnapshot.StateEnum.PASSWORD_CHANGE_REQUIRED)
            throw new ConsoleSessionException(ConsoleSessionException.Code.INITIAL_CREDENTIAL_RESTRICTED);
        return response(Long.parseLong(snapshot.getRevision())).body(new ConsoleAvailableContextsResult(
                snapshot.getSessionId(), snapshot.getRevision(), snapshot.getAvailableContexts()));
    }

    @Override
    public ResponseEntity<Void> logoutConsoleSession(String csrf, String revision, UUID key, Object body) {
        requireEmpty(body);
        var result = termination.logout(cookie(SLOT_COOKIE), revision, key);
        var response = ResponseEntity.noContent().eTag(Long.toString(result.revision())).header(HttpHeaders.CACHE_CONTROL, "no-store");
        if (result.clearRefreshCookie()) response.header(HttpHeaders.SET_COOKIE, cookie(REFRESH_COOKIE, "", 0));
        return response.build();
    }

    private ConsoleSessionSnapshot snapshot() {
        return snapshot(sessions.session(cookie(SLOT_COOKIE), cookie(REFRESH_COOKIE)));
    }

    private ResponseEntity<ConsoleAuthenticationResult> authentication(ConsoleSessionService.Authentication result) {
        var snapshot = snapshot(result.snapshot());
        var body = new ConsoleAuthenticationResult(snapshot.getSessionId(), snapshot.getRevision(),
                ConsoleAuthenticationResult.StateEnum.valueOf(snapshot.getState().name()))
                .identity(snapshot.getIdentity()).activeContext(snapshot.getActiveContext())
                .availableContexts(snapshot.getAvailableContexts());
        if (result.token() != null) body.accessToken(result.token().value())
                .tokenType(ConsoleAuthenticationResult.TokenTypeEnum.BEARER).expiresIn(result.token().expiresInSeconds());
        return response(result.snapshot().revision())
                .header(HttpHeaders.SET_COOKIE, cookie(REFRESH_COOKIE, result.refresh(), result.maxAge()),
                        cookie(SLOT_COOKIE, cookie(SLOT_COOKIE), 28800))
                .body(body);
    }

    private static ConsoleSessionSnapshot snapshot(io.saas.forge.iam.application.authentication.ConsoleSessionSnapshot result) {
        var family = result.family();
        var snapshot = new ConsoleSessionSnapshot(family.id(), Long.toString(result.revision()),
                ConsoleSessionSnapshot.StateEnum.valueOf(result.state().name()));
        if (result.state() == io.saas.forge.iam.application.authentication.ConsoleSessionSnapshot.State.PASSWORD_CHANGE_REQUIRED)
            return snapshot;
        ConsoleActiveContext active = null;
        if (result.state() == io.saas.forge.iam.application.authentication.ConsoleSessionSnapshot.State.AUTHENTICATED)
            active = new ConsoleActiveContext(family.purpose() == RefreshTokenFamilyPurpose.USER_PLATFORM
                    ? ConsoleActiveContext.TypeEnum.PLATFORM : ConsoleActiveContext.TypeEnum.TENANT)
                    .membershipId(family.membershipId()).tenantId(family.tenantId());
        var companies = result.companies().stream().map(company -> new ConsoleCompany(company.membershipId(),
                company.tenantId(), company.tenantDisplayName())).toList();
        return snapshot.identity(new ConsoleIdentity(result.identity().id(), result.identity().email().value()))
                .activeContext(active).availableContexts(new ConsoleAvailableContexts(result.platform(), companies));
    }

    private String cookie(String name) {
        HttpServletRequest request = ((org.springframework.web.context.request.ServletRequestAttributes)
                org.springframework.web.context.request.RequestContextHolder.currentRequestAttributes()).getRequest();
        if (request.getCookies() == null) return null;
        var values = Arrays.stream(request.getCookies()).filter(cookie -> name.equals(cookie.getName())).toList();
        if (values.size() > 1) throw new ConsoleSessionException(ConsoleSessionException.Code.VALIDATION_FAILED);
        return values.isEmpty() ? null : values.get(0).getValue();
    }

    private static String cookie(String name, String value, long maxAge) {
        return ResponseCookie.from(name, value).secure(true).httpOnly(true).sameSite("Strict").path("/").maxAge(maxAge).build().toString();
    }

    private static ResponseEntity.BodyBuilder response(long revision) {
        return ResponseEntity.ok().eTag(Long.toString(revision)).header(HttpHeaders.CACHE_CONTROL, "no-store");
    }

    private static void requireEmpty(Object body) {
        if (!(body instanceof java.util.Map<?, ?> map) || !map.isEmpty())
            throw new ConsoleSessionException(ConsoleSessionException.Code.VALIDATION_FAILED);
    }
}
