package io.saas.forge.iam.config;

import io.saas.forge.iam.application.authentication.*;
import io.saas.forge.iam.domain.authorization.PlatformRoleAssignmentRepository;
import io.saas.forge.iam.domain.identity.IdentityRepository;
import io.saas.forge.iam.domain.outbox.OutboxEventRepository;
import io.saas.forge.iam.domain.session.*;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "security.browser.console-enabled", havingValue = "true")
public class ConsoleSessionConfiguration {
    @Bean
    ConsoleSessionAccess consoleSessionAccess(ConsoleSessionRepository slots, RefreshTokenFamilyRepository families,
            RefreshTokenIssuer tokens, Clock clock) {
        return new ConsoleSessionAccess(slots, families, tokens, clock);
    }

    @Bean
    ConsoleSessionAuthority consoleSessionAuthority(IdentityRepository identities, PlatformRoleAssignmentRepository roles,
            AccessibleMemberships memberships, Clock clock) {
        return new ConsoleSessionAuthority(identities, roles, memberships, clock);
    }

    @Bean
    ConsoleSessionTermination consoleSessionTermination(ConsoleSessionAccess access, ConsoleSessionRepository slots,
            RefreshTokenFamilyRepository families, AccessTokenIssuanceRepository issuances, RevocationIndex index,
            OutboxEventRepository outbox, SessionRevokedEventFactory events, RefreshTokenIssuer tokens,
            PlatformTransactionManager manager, Clock clock) {
        return new ConsoleSessionTermination(access, slots, families, issuances, index, outbox, events, tokens,
                new TransactionTemplate(manager), clock);
    }

    @Bean
    ConsoleContextSelectionService consoleContextSelectionService(ConsoleSessionAccess access,
            ConsoleSessionRepository slots, RefreshTokenFamilyRepository families,
            AccessTokenIssuanceRepository issuances, ConsoleSessionAuthority authority,
            ConsoleSessionTermination termination, RevocationIndex revocations, OutboxEventRepository outbox,
            TenantContextSwitchedEventFactory switchedEvents, UserTokenIssuanceFence issuanceFence,
            PlatformTransactionManager manager, UuidV7Generator keys, Clock clock) {
        return new ConsoleContextSelectionService(access, slots, families, issuances, authority, termination,
                revocations, outbox, switchedEvents, issuanceFence, new TransactionTemplate(manager), keys, clock);
    }

    @Bean
    ConsolePasswordChangeService consolePasswordChangeService(ConsoleSessionAccess access,
            ConsoleSessionRepository slots, InitialPasswordChangeService passwords, IdentityRepository identities,
            PasswordVerifier verifier, PlatformTransactionManager manager) {
        return new ConsolePasswordChangeService(access, slots, passwords, identities, verifier,
                new TransactionTemplate(manager));
    }

    @Bean
    ConsoleSessionService consoleSessionService(ConsoleSessionAccess access, ConsoleSessionRepository slots,
            RefreshTokenFamilyRepository families, AccessTokenIssuanceRepository issuances,
            IdentityRepository identities, LoginProtection protection, PasswordVerifier verifier,
            ConsoleSessionAuthority authority, ConsoleSessionTermination termination, RefreshTokenIssuer refreshTokens,
            UserAccessTokenIssuer accessTokens, RefreshRotationLease leases, RefreshRotationTransaction rotations,
            OutboxEventRepository outbox, SessionStartedEventFactory events, PlatformTransactionManager manager,
            UuidV7Generator keys, Clock clock, UserTokenIssuanceFence issuanceFence) {
        return new ConsoleSessionService(access, slots, families, issuances,
                new PasswordAuthenticator(identities, protection, verifier, clock), authority, termination,
                refreshTokens, accessTokens, leases, rotations, outbox, events, new TransactionTemplate(manager), keys, clock, issuanceFence);
    }
}
