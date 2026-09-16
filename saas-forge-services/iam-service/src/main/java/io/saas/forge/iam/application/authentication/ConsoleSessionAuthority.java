package io.saas.forge.iam.application.authentication;

import static io.saas.forge.iam.application.authentication.ConsoleSessionException.Code.*;
import io.saas.forge.iam.domain.authorization.PlatformRoleAssignmentRepository;
import io.saas.forge.iam.domain.identity.IdentityRepository;
import io.saas.forge.iam.domain.session.RefreshTokenFamily;
import io.saas.forge.iam.domain.session.RefreshTokenFamilyPurpose;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

public final class ConsoleSessionAuthority {
    private final IdentityRepository identities;
    private final PlatformRoleAssignmentRepository roles;
    private final AccessibleMemberships memberships;
    private final Clock clock;

    public ConsoleSessionAuthority(IdentityRepository identities, PlatformRoleAssignmentRepository roles,
                                   AccessibleMemberships memberships, Clock clock) {
        this.identities = identities; this.roles = roles; this.memberships = memberships; this.clock = clock;
    }

    public Contexts contexts(UUID identityId) {
        var companies = memberships.findByIdentityId(identityId);
        // 当前内部接口超过完整性上限时停止登录判断，绝不把截断列表当作唯一候选。
        if (companies.size() > 100) throw new ConsoleSessionException(CONTEXTS_UNAVAILABLE);
        return new Contexts(roles.hasActiveAssignment(identityId, "PLATFORM_ADMIN", clock.instant()), companies);
    }

    public ConsoleSessionSnapshot snapshot(long revision, RefreshTokenFamily family) {
        var identity = identities.findById(family.identityId()).orElseThrow(() -> new ConsoleSessionException(SESSION_INVALID));
        if (family.purpose() == RefreshTokenFamilyPurpose.INITIAL_PASSWORD_CHANGE) {
            if (identities.findCredential(family.initialCredentialId()).filter(value -> value.isValidAt(clock.instant())).isEmpty())
                throw new ConsoleSessionException(SESSION_INVALID);
            return new ConsoleSessionSnapshot(revision, family, identity,
                    ConsoleSessionSnapshot.State.PASSWORD_CHANGE_REQUIRED, false, List.of());
        }
        var contexts = contexts(family.identityId());
        boolean selected = family.purpose() != RefreshTokenFamilyPurpose.USER_TENANT_SELECTION;
        if (selected && (family.purpose() == RefreshTokenFamilyPurpose.USER_PLATFORM ? !contexts.platform()
                : contexts.companies().stream().noneMatch(company -> company.membershipId().equals(family.membershipId())
                        && company.tenantId().equals(family.tenantId()))))
            throw new ConsoleSessionException(CURRENT_CONTEXT_REVOKED);
        var state = selected ? ConsoleSessionSnapshot.State.AUTHENTICATED
                : contexts.count() == 0 ? ConsoleSessionSnapshot.State.NO_AVAILABLE_CONTEXT
                : ConsoleSessionSnapshot.State.CONTEXT_SELECTION_REQUIRED;
        return new ConsoleSessionSnapshot(revision, family, identity, state, contexts.platform(), contexts.companies());
    }

    public record Contexts(boolean platform, List<AccessibleMembership> companies) {
        public Contexts { companies = List.copyOf(companies); }
        public int count() { return companies.size() + (platform ? 1 : 0); }
    }
}
