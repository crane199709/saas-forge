package io.saas.forge.iam.api;

import io.saas.forge.iam.application.authentication.AccessContextUnavailableException;
import io.saas.forge.iam.application.authentication.AccessibleMembershipLimitExceededException;
import io.saas.forge.iam.application.authentication.AuthenticationFailedException;
import io.saas.forge.iam.application.authentication.AuthenticationProtectionUnavailableException;
import io.saas.forge.iam.application.authentication.ContextSelectionRejectedException;
import io.saas.forge.iam.application.authentication.ContextSelectionSessionInvalidException;
import io.saas.forge.iam.application.authentication.ClientCredentialsGrantInvalidException;
import io.saas.forge.iam.application.authentication.ClientCredentialsInvalidException;
import io.saas.forge.iam.application.authentication.ClientCredentialsScopeRejectedException;
import io.saas.forge.iam.application.authentication.LogoutUnavailableException;
import io.saas.forge.iam.application.authentication.RevocationIndexUnavailableException;
import io.saas.forge.iam.application.authentication.TokenRevocationStatusUnavailableException;
import io.saas.forge.iam.application.authentication.TenantAccessUnavailableException;
import io.saas.forge.iam.application.authentication.PasswordChangeSessionInvalidException;
import io.saas.forge.iam.application.authentication.PasswordCompromisedException;
import io.saas.forge.iam.application.authentication.PasswordPolicyException;
import io.saas.forge.iam.application.authentication.PasswordSetupTokenInvalidException;
import io.saas.forge.iam.application.authentication.RefreshAuthorizationRejectedException;
import io.saas.forge.iam.application.authentication.RefreshContextChangedException;
import io.saas.forge.iam.application.authentication.RefreshSessionInvalidException;
import io.saas.forge.iam.application.authentication.RefreshRotationInProgressException;
import io.saas.forge.iam.application.authentication.RefreshRotationUnavailableException;
import io.saas.forge.iam.application.authentication.BrowserRequestRejectedException;
import io.saas.forge.iam.application.authentication.BrowserSessionSlot;
import io.saas.forge.iam.application.authentication.SessionSlotAlreadyActiveException;
import io.saas.forge.iam.application.authentication.TenantContextSwitchAccessRejectedException;
import io.saas.forge.iam.application.authentication.TenantContextSwitchConflictException;
import io.saas.forge.iam.application.authentication.TenantContextSwitchPendingException;
import io.saas.forge.iam.application.authentication.TenantContextSwitchSessionInvalidException;
import io.saas.forge.sdk.auth.UserAccessTokenInvalidException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestCookieException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class AuthenticationExceptionHandler {
    private static final String LEGACY_REFRESH_COOKIE = "__Host-sf_refresh";
    private static final Pattern TRACE_PARENT = Pattern.compile(
            "^[0-9a-f]{2}-((?!0{32})[0-9a-f]{32})-(?!0{16})[0-9a-f]{16}-[0-9a-f]{2}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    @ExceptionHandler(UserAccessTokenInvalidException.class)
    ResponseEntity<Problem> accessTokenInvalid(
            UserAccessTokenInvalidException exception, HttpServletRequest request) {
        return problem(HttpStatus.UNAUTHORIZED, "ACCESS_TOKEN_INVALID",
                "Access token invalid", "User Access Token 无效", request);
    }

    @ExceptionHandler(ClientCredentialsGrantInvalidException.class)
    ResponseEntity<Problem> clientCredentialsGrantInvalid(
            ClientCredentialsGrantInvalidException exception, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "CLIENT_CREDENTIALS_GRANT_INVALID",
                "Client credentials grant invalid", exception.getMessage(), request);
    }

    @ExceptionHandler(ClientCredentialsInvalidException.class)
    ResponseEntity<Problem> clientCredentialsInvalid(
            ClientCredentialsInvalidException exception, HttpServletRequest request) {
        return problem(HttpStatus.UNAUTHORIZED, "CLIENT_CREDENTIALS_INVALID",
                "Client credentials invalid", exception.getMessage(), request);
    }

    @ExceptionHandler(ClientCredentialsScopeRejectedException.class)
    ResponseEntity<Problem> clientCredentialsScopeRejected(
            ClientCredentialsScopeRejectedException exception, HttpServletRequest request) {
        return problem(HttpStatus.FORBIDDEN, "CLIENT_CREDENTIALS_SCOPE_REJECTED",
                "Client credentials scope rejected", exception.getMessage(), request);
    }

    @ExceptionHandler(AuthenticationFailedException.class)
    ResponseEntity<Problem> authenticationFailed(AuthenticationFailedException exception, HttpServletRequest request) {
        return problem(HttpStatus.UNAUTHORIZED, AuthenticationFailedException.CODE,
                "Authentication failed", exception.getMessage(), request);
    }

    @ExceptionHandler(SessionSlotAlreadyActiveException.class)
    ResponseEntity<Problem> sessionSlotAlreadyActive(
            SessionSlotAlreadyActiveException exception, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, SessionSlotAlreadyActiveException.CODE,
                "Session slot already active", exception.getMessage(), request);
    }

    @ExceptionHandler(AccessContextUnavailableException.class)
    ResponseEntity<Problem> accessContextUnavailable(
            AccessContextUnavailableException exception, HttpServletRequest request) {
        return problem(HttpStatus.FORBIDDEN, AccessContextUnavailableException.CODE,
                "Access context unavailable", exception.getMessage(), request);
    }

    @ExceptionHandler(AuthenticationProtectionUnavailableException.class)
    ResponseEntity<Problem> authenticationProtectionUnavailable(
            AuthenticationProtectionUnavailableException exception, HttpServletRequest request) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, AuthenticationProtectionUnavailableException.CODE,
                "Authentication protection unavailable", exception.getMessage(), request);
    }

    @ExceptionHandler(RevocationIndexUnavailableException.class)
    ResponseEntity<Problem> revocationIndexUnavailable(
            RevocationIndexUnavailableException exception, HttpServletRequest request) {
        return problemWithClearedRefreshCookie(
                HttpStatus.SERVICE_UNAVAILABLE, RevocationIndexUnavailableException.CODE,
                "Revocation index unavailable", exception.getMessage(), request);
    }

    @ExceptionHandler(TokenRevocationStatusUnavailableException.class)
    ResponseEntity<Problem> tokenRevocationStatusUnavailable(
            TokenRevocationStatusUnavailableException exception, HttpServletRequest request) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, TokenRevocationStatusUnavailableException.CODE,
                "Token revocation status unavailable", exception.getMessage(), request);
    }

    @ExceptionHandler(LogoutUnavailableException.class)
    ResponseEntity<Problem> logoutUnavailable(
            LogoutUnavailableException exception, HttpServletRequest request) {
        return problemWithClearedRefreshCookie(
                HttpStatus.SERVICE_UNAVAILABLE, LogoutUnavailableException.CODE,
                "Logout unavailable", exception.getMessage(), request);
    }

    @ExceptionHandler(AccessibleMembershipLimitExceededException.class)
    ResponseEntity<Problem> accessibleMembershipLimitExceeded(
            AccessibleMembershipLimitExceededException exception, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, AccessibleMembershipLimitExceededException.CODE,
                "Accessible membership limit exceeded", exception.getMessage(), request);
    }

    @ExceptionHandler(TenantAccessUnavailableException.class)
    ResponseEntity<Problem> tenantAccessUnavailable(
            TenantAccessUnavailableException exception, HttpServletRequest request) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, TenantAccessUnavailableException.CODE,
                "Tenant Access unavailable", exception.getMessage(), request);
    }

    @ExceptionHandler(BrowserRequestRejectedException.class)
    ResponseEntity<Problem> browserRequestRejected(
            BrowserRequestRejectedException exception, HttpServletRequest request) {
        return problem(HttpStatus.FORBIDDEN, BrowserRequestRejectedException.CODE,
                "Browser request rejected", exception.getMessage(), request);
    }

    @ExceptionHandler(TenantContextSwitchSessionInvalidException.class)
    ResponseEntity<Problem> tenantContextSwitchSessionInvalid(
            TenantContextSwitchSessionInvalidException exception, HttpServletRequest request) {
        return problemWithClearedRefreshCookie(
                HttpStatus.UNAUTHORIZED, TenantContextSwitchSessionInvalidException.CODE,
                "Tenant context switch session invalid", exception.getMessage(), request);
    }

    @ExceptionHandler(TenantContextSwitchAccessRejectedException.class)
    ResponseEntity<Problem> tenantContextSwitchAccessRejected(
            TenantContextSwitchAccessRejectedException exception, HttpServletRequest request) {
        if (exception.clearRefreshCookie()) {
            return problemWithClearedRefreshCookie(
                    HttpStatus.FORBIDDEN, TenantContextSwitchAccessRejectedException.CODE,
                    "Access context unavailable", exception.getMessage(), request);
        }
        return problem(HttpStatus.FORBIDDEN, TenantContextSwitchAccessRejectedException.CODE,
                "Access context unavailable", exception.getMessage(), request);
    }

    @ExceptionHandler(TenantContextSwitchConflictException.class)
    ResponseEntity<Problem> tenantContextSwitchConflict(
            TenantContextSwitchConflictException exception, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, exception.code(),
                "Tenant context switch conflict", exception.getMessage(), request);
    }

    @ExceptionHandler(TenantContextSwitchPendingException.class)
    ResponseEntity<Problem> tenantContextSwitchPending(
            TenantContextSwitchPendingException exception, HttpServletRequest request) {
        Problem body = problemBody(HttpStatus.SERVICE_UNAVAILABLE, TenantContextSwitchPendingException.CODE,
                "Tenant context switch pending", exception.getMessage(), request);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(exception.retryAfterSeconds()))
                .body(body);
    }

    @ExceptionHandler(ContextSelectionSessionInvalidException.class)
    ResponseEntity<Problem> contextSelectionSessionInvalid(
            ContextSelectionSessionInvalidException exception, HttpServletRequest request) {
        return problemWithClearedRefreshCookie(
                HttpStatus.UNAUTHORIZED, ContextSelectionSessionInvalidException.CODE,
                "Context selection session invalid", exception.getMessage(), request);
    }

    @ExceptionHandler(ContextSelectionRejectedException.class)
    ResponseEntity<Problem> contextSelectionRejected(
            ContextSelectionRejectedException exception, HttpServletRequest request) {
        return problemWithClearedRefreshCookie(
                HttpStatus.FORBIDDEN, ContextSelectionRejectedException.CODE,
                "Context selection rejected", exception.getMessage(), request);
    }

    @ExceptionHandler(PasswordPolicyException.class)
    ResponseEntity<Problem> passwordPolicy(PasswordPolicyException exception, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, exception.code(),
                "Password policy violation", exception.getMessage(), request);
    }

    @ExceptionHandler(PasswordCompromisedException.class)
    ResponseEntity<Problem> passwordCompromised(
            PasswordCompromisedException exception, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, PasswordCompromisedException.CODE,
                "Password compromised", exception.getMessage(), request);
    }

    @ExceptionHandler(PasswordSetupTokenInvalidException.class)
    ResponseEntity<Problem> passwordSetupTokenInvalid(
            PasswordSetupTokenInvalidException exception, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, PasswordSetupTokenInvalidException.CODE,
                "Password setup token invalid", exception.getMessage(), request);
    }

    @ExceptionHandler(PasswordChangeSessionInvalidException.class)
    ResponseEntity<Problem> passwordChangeSessionInvalid(
            PasswordChangeSessionInvalidException exception, HttpServletRequest request) {
        return problemWithClearedRefreshCookie(
                HttpStatus.UNAUTHORIZED, PasswordChangeSessionInvalidException.CODE,
                "Password change session invalid", exception.getMessage(), request);
    }

    @ExceptionHandler(RefreshSessionInvalidException.class)
    ResponseEntity<Problem> refreshSessionInvalid(
            RefreshSessionInvalidException exception, HttpServletRequest request) {
        return problemWithClearedRefreshCookie(
                HttpStatus.UNAUTHORIZED, RefreshSessionInvalidException.CODE,
                "Refresh session invalid", exception.getMessage(), request);
    }

    @ExceptionHandler(RefreshRotationInProgressException.class)
    ResponseEntity<Problem> refreshRotationInProgress(
            RefreshRotationInProgressException exception, HttpServletRequest request) {
        Problem body = problemBody(HttpStatus.CONFLICT, RefreshRotationInProgressException.CODE,
                "Refresh rotation in progress", exception.getMessage(), request);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(exception.retryAfterSeconds()))
                .body(body);
    }

    @ExceptionHandler(RefreshContextChangedException.class)
    ResponseEntity<Problem> refreshContextChanged(
            RefreshContextChangedException exception, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, RefreshContextChangedException.CODE,
                "Refresh context changed", exception.getMessage(), request);
    }

    @ExceptionHandler(RefreshRotationUnavailableException.class)
    ResponseEntity<Problem> refreshRotationUnavailable(
            RefreshRotationUnavailableException exception, HttpServletRequest request) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, RefreshRotationUnavailableException.CODE,
                "Refresh rotation unavailable", exception.getMessage(), request);
    }

    @ExceptionHandler(RefreshAuthorizationRejectedException.class)
    ResponseEntity<Problem> refreshAuthorizationRejected(
            RefreshAuthorizationRejectedException exception, HttpServletRequest request) {
        return problemWithClearedRefreshCookie(
                HttpStatus.FORBIDDEN, RefreshAuthorizationRejectedException.CODE,
                "Access context unavailable", exception.getMessage(), request);
    }

    @ExceptionHandler(MissingRequestCookieException.class)
    ResponseEntity<Problem> missingRefreshCookie(
            MissingRequestCookieException exception, HttpServletRequest request) throws MissingRequestCookieException {
        if (!BrowserSessionSlot.PLATFORM.cookieName().equals(exception.getCookieName())
                && !BrowserSessionSlot.TENANT.cookieName().equals(exception.getCookieName())) {
            throw exception;
        }
        if (request.getRequestURI().endsWith("/password-changes")) {
            return problemWithClearedRefreshCookie(
                    HttpStatus.UNAUTHORIZED, PasswordChangeSessionInvalidException.CODE,
                    "Password change session invalid", "首次改密会话无效或已失效", request);
        }
        if (request.getRequestURI().endsWith("/refresh")) {
            return problemWithClearedRefreshCookie(
                    HttpStatus.UNAUTHORIZED, RefreshSessionInvalidException.CODE,
                    "Refresh session invalid", "Refresh 会话无效或已失效", request);
        }
        if (request.getRequestURI().endsWith("/tenant-switches")) {
            return problemWithClearedRefreshCookie(
                    HttpStatus.UNAUTHORIZED, TenantContextSwitchSessionInvalidException.CODE,
                    "Tenant context switch session invalid", "Tenant Context Switch 会话无效或已失效", request);
        }
        return problemWithClearedRefreshCookie(HttpStatus.UNAUTHORIZED, ContextSelectionSessionInvalidException.CODE,
                "Context selection session invalid", "Tenant 上下文选择会话无效或已失效", request);
    }

    private ResponseEntity<Problem> problem(
            HttpStatus status, String code, String title, String detail, HttpServletRequest request) {
        Problem body = problemBody(status, code, title, detail, request);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }

    private Problem problemBody(
            HttpStatus status, String code, String title, String detail, HttpServletRequest request) {
        return new Problem(URI.create("urn:saas.forge:problem:" + code.toLowerCase().replace('_', '-')),
                title, status.value(), code, detail, traceId(request));
    }

    private ResponseEntity<Problem> problemWithClearedRefreshCookie(
            HttpStatus status, String code, String title, String detail, HttpServletRequest request) {
        Problem body = new Problem(URI.create("urn:saas.forge:problem:" + code.toLowerCase().replace('_', '-')),
                title, status.value(), code, detail, traceId(request));
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .header(HttpHeaders.SET_COOKIE,
                        clearedCookie(selectedCookieName(request)).toString(),
                        clearedCookie(LEGACY_REFRESH_COOKIE).toString())
                .body(body);
    }

    private String selectedCookieName(HttpServletRequest request) {
        Object slot = request.getAttribute(AuthenticationController.SESSION_SLOT_ATTRIBUTE);
        if (slot instanceof BrowserSessionSlot browserSessionSlot) {
            return browserSessionSlot.cookieName();
        }
        String path = request.getRequestURI();
        return path.endsWith("/password-changes")
                ? BrowserSessionSlot.PLATFORM.cookieName()
                : BrowserSessionSlot.TENANT.cookieName();
    }

    private ResponseCookie clearedCookie(String cookieName) {
        return ResponseCookie.from(cookieName, "")
                .secure(true)
                .httpOnly(true)
                .sameSite("Strict")
                .path("/")
                .maxAge(0)
                .build();
    }

    static String traceId(HttpServletRequest request) {
        Matcher matcher = TRACE_PARENT.matcher(request.getHeader("traceparent") == null
                ? ""
                : request.getHeader("traceparent"));
        if (matcher.matches()) {
            return matcher.group(1);
        }
        byte[] traceId = new byte[16];
        do {
            RANDOM.nextBytes(traceId);
        } while (allZero(traceId));
        return HexFormat.of().formatHex(traceId);
    }

    private static boolean allZero(byte[] value) {
        for (byte current : value) {
            if (current != 0) {
                return false;
            }
        }
        return true;
    }

    record Problem(URI type, String title, int status, String code, String detail, String traceId) {
    }
}
