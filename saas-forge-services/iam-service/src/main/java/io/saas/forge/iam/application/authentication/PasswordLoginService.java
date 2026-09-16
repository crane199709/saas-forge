package io.saas.forge.iam.application.authentication;

import io.saas.forge.iam.domain.authorization.PlatformRoleAssignmentRepository;
import io.saas.forge.iam.domain.identity.CredentialType;
import io.saas.forge.iam.domain.identity.Identity;
import io.saas.forge.iam.domain.identity.IdentityRepository;
import io.saas.forge.iam.domain.identity.PasswordCredential;
import io.saas.forge.iam.domain.session.RefreshTokenFamilyPurpose;
import io.saas.forge.iam.domain.session.RefreshTokenFamilyRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

public final class PasswordLoginService {
    private static final int ACCESSIBLE_MEMBERSHIP_LIMIT = 100;
    private static final String PLATFORM_ADMIN_ROLE = "PLATFORM_ADMIN";

    private final IdentityRepository identities;
    private final PlatformRoleAssignmentRepository platformRoles;
    private final AccessibleMemberships accessibleMemberships;
    private final LoginProtection loginProtection;
    private final PasswordVerifier passwordVerifier;
    private final UserAccessTokenIssuer accessTokenIssuer;
    private final RefreshTokenIssuer refreshTokenIssuer;
    private final RefreshTokenFamilyRepository refreshTokenFamilies;
    private final LoginSessionService sessionService;
    private final Clock clock;

    public PasswordLoginService(
            IdentityRepository identities,
            PlatformRoleAssignmentRepository platformRoles,
            AccessibleMemberships accessibleMemberships,
            LoginProtection loginProtection,
            PasswordVerifier passwordVerifier,
            UserAccessTokenIssuer accessTokenIssuer,
            RefreshTokenIssuer refreshTokenIssuer,
            RefreshTokenFamilyRepository refreshTokenFamilies,
            LoginSessionService sessionService,
            Clock clock) {
        this.identities = identities;
        this.platformRoles = platformRoles;
        this.accessibleMemberships = accessibleMemberships;
        this.loginProtection = loginProtection;
        this.passwordVerifier = passwordVerifier;
        this.accessTokenIssuer = accessTokenIssuer;
        this.refreshTokenIssuer = refreshTokenIssuer;
        this.refreshTokenFamilies = refreshTokenFamilies;
        this.sessionService = sessionService;
        this.clock = clock;
    }

    public LoginResult login(
            String email,
            String password,
            LoginContextType contextType,
            String selectedRefreshToken,
            String traceId) {
        requireAvailableSlot(BrowserSessionSlot.forLogin(contextType), selectedRefreshToken);
        var authenticated = new PasswordAuthenticator(identities, loginProtection, passwordVerifier, clock)
                .authenticate(email, password);
        Instant now = authenticated.authenticatedAt();
        Identity authenticatedIdentity = authenticated.identity();
        PasswordCredential authenticatedCredential = authenticated.credential();
        if (authenticatedCredential.type() == CredentialType.INITIAL_PLATFORM_PASSWORD) {
            if (contextType != LoginContextType.PLATFORM) {
                throw new BrowserRequestRejectedException();
            }
            return initialPasswordChangeLogin(authenticatedIdentity, authenticatedCredential, now, traceId);
        }
        return switch (contextType) {
            case PLATFORM -> platformLogin(authenticatedIdentity, now, traceId);
            case TENANT -> tenantLogin(authenticatedIdentity, traceId);
        };
    }

    private void requireAvailableSlot(BrowserSessionSlot slot, String selectedRefreshToken) {
        if (selectedRefreshToken == null) {
            return;
        }
        io.saas.forge.iam.domain.shared.Sha256Digest digest;
        try {
            digest = refreshTokenIssuer.digest(selectedRefreshToken);
        } catch (ContextSelectionSessionInvalidException invalidRefreshToken) {
            // 无效或无法解析的旧槽位 Cookie 不阻止重新登录；成功响应会覆盖它。
            return;
        }
        refreshTokenFamilies.findByTokenDigest(digest).ifPresent(family -> {
            if (!slot.accepts(family.purpose())) {
                throw new BrowserRequestRejectedException();
            }
            if (family.isUsableAt(clock.instant())) {
                throw new SessionSlotAlreadyActiveException();
            }
        });
    }

    private LoginResult initialPasswordChangeLogin(
            Identity identity, PasswordCredential credential, Instant now, String traceId) {
        RefreshTokenMaterial refreshToken = refreshTokenIssuer.issue();
        long cookieMaxAge = sessionService.startInitialPasswordChangeSession(
                identity.id(), credential.id(), credential.expiresAt(), refreshToken, now, traceId);
        return new InitialPasswordChangeLoginResult(refreshToken.value(), cookieMaxAge);
    }

    private LoginResult platformLogin(Identity identity, Instant now, String traceId) {
        if (!platformRoles.hasActiveAssignment(identity.id(), PLATFORM_ADMIN_ROLE, now)) {
            throw new AccessContextUnavailableException();
        }
        IssuedAccessToken accessToken = accessTokenIssuer.issueUserToken(identity.id(), null, null);
        RefreshTokenMaterial refreshToken = refreshTokenIssuer.issue();
        long cookieMaxAge = sessionService.startAccessTokenSession(
                identity.id(), RefreshTokenFamilyPurpose.USER_PLATFORM, null, null,
                accessToken, refreshToken, traceId);
        return new AccessTokenLoginResult(accessToken, refreshToken.value(), cookieMaxAge);
    }

    private LoginResult tenantLogin(Identity identity, String traceId) {
        List<AccessibleMembership> memberships = accessibleMemberships.findByIdentityId(identity.id());
        if (memberships.isEmpty()) {
            throw new AccessContextUnavailableException();
        }
        if (memberships.size() > ACCESSIBLE_MEMBERSHIP_LIMIT) {
            throw new AccessibleMembershipLimitExceededException();
        }
        RefreshTokenMaterial refreshToken = refreshTokenIssuer.issue();
        if (memberships.size() == 1) {
            AccessibleMembership membership = memberships.get(0);
            IssuedAccessToken accessToken = accessTokenIssuer.issueUserToken(
                    identity.id(), membership.membershipId(), membership.tenantId());
            long cookieMaxAge = sessionService.startAccessTokenSession(
                    identity.id(), RefreshTokenFamilyPurpose.USER_TENANT,
                    membership.membershipId(), membership.tenantId(), accessToken, refreshToken, traceId);
            return new AccessTokenLoginResult(
                    accessToken,
                    refreshToken.value(),
                    cookieMaxAge,
                    new TenantAuthenticationContextSnapshot(membership, memberships));
        }
        long cookieMaxAge = sessionService.startSelectionSession(identity.id(), refreshToken, clock.instant(), traceId);
        return new ContextSelectionLoginResult(memberships, refreshToken.value(), cookieMaxAge);
    }

}
