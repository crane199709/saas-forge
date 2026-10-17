package io.saas.forge.iam.config;

import io.saas.forge.iam.application.authentication.CurrentSessionQuery;
import io.saas.forge.contracts.tenantaccess.membership.v1.AccessibleMembershipQueryServiceGrpc;
import io.saas.forge.iam.application.authentication.AccessibleMemberships;
import io.saas.forge.iam.application.authentication.CurrentTenantContextQuery;
import io.saas.forge.sdk.auth.UserAccessTokenSignatureVerifier;
import io.saas.forge.iam.application.authentication.ContextSelectionService;
import io.saas.forge.iam.application.authentication.ClientCredentialsTokenService;
import io.saas.forge.iam.application.authentication.CompromisedPasswordChecker;
import io.saas.forge.iam.application.authentication.InitialPasswordChangeService;
import io.saas.forge.iam.application.authentication.LoginProtection;
import io.saas.forge.iam.application.authentication.LoginSessionService;
import io.saas.forge.iam.application.authentication.LogoutService;
import io.saas.forge.iam.application.authentication.LogoutTransaction;
import io.saas.forge.iam.application.authentication.PasswordVerifier;
import io.saas.forge.iam.application.authentication.PasswordPolicy;
import io.saas.forge.iam.application.authentication.PasswordChangedEventFactory;
import io.saas.forge.iam.application.authentication.PasswordLoginService;
import io.saas.forge.iam.application.authentication.PasswordEstablishedEventFactory;
import io.saas.forge.iam.application.authentication.PasswordSetupChallengeIssuer;
import io.saas.forge.iam.application.authentication.PasswordSetupDeliveredEventFactory;
import io.saas.forge.iam.application.authentication.PasswordSetupDeliveryService;
import io.saas.forge.iam.application.authentication.PasswordSetupDeliveryTransaction;
import io.saas.forge.iam.application.authentication.PasswordSetupMailer;
import io.saas.forge.iam.application.authentication.PasswordSetupService;
import io.saas.forge.iam.application.authentication.PasswordRecoveryService;
import io.saas.forge.iam.application.authentication.PasswordRecoveryMailer;
import io.saas.forge.iam.application.authentication.PasswordRecoveredEventFactory;
import io.saas.forge.iam.application.authentication.PasswordRecoveryWorker;
import io.saas.forge.iam.application.authentication.RefreshTokenIssuer;
import io.saas.forge.iam.application.authentication.RevocationIndex;
import io.saas.forge.iam.application.authentication.RevocationIndexRecovery;
import io.saas.forge.iam.application.authentication.RevocationFenceService;
import io.saas.forge.iam.application.authentication.RevocationFenceOperations;
import io.saas.forge.iam.application.authentication.PresentedAccessTokenVerifier;
import io.saas.forge.iam.application.authentication.RefreshSessionService;
import io.saas.forge.iam.application.authentication.RefreshRotationLease;
import io.saas.forge.iam.application.authentication.RefreshRotationTransaction;
import io.saas.forge.iam.application.authentication.RefreshReplayDetectedEventFactory;
import io.saas.forge.iam.application.authentication.SessionStartedEventFactory;
import io.saas.forge.iam.application.authentication.SessionRevokedEventFactory;
import io.saas.forge.iam.application.authentication.UserAccessTokenIssuer;
import io.saas.forge.iam.application.authentication.UserTokenIssuanceFence;
import io.saas.forge.iam.application.authentication.UserSessionRevocationRecoveryPolicy;
import io.saas.forge.iam.application.authentication.UserSessionRevocationService;
import io.saas.forge.iam.application.authentication.UserSessionRevocationWorker;
import io.saas.forge.iam.application.authentication.UserSessionRevocationTransaction;
import io.saas.forge.iam.application.authentication.UserSessionsRevokedEventFactory;
import io.saas.forge.iam.application.authentication.ServiceAccessTokenIssuer;
import io.saas.forge.iam.application.authentication.MembershipValidation;
import io.saas.forge.iam.application.authentication.TenantContextSwitchService;
import io.saas.forge.iam.application.authentication.TenantContextSwitchRecoveryPolicy;
import io.saas.forge.iam.application.authentication.TenantContextSwitchWorker;
import io.saas.forge.iam.application.authentication.TenantContextSwitchedEventFactory;
import io.saas.forge.iam.application.authentication.TenantContextSwitchTransaction;
import io.saas.forge.iam.application.authentication.UuidV7Generator;
import io.saas.forge.iam.application.authorization.PlatformRoleAuthorizationService;
import io.saas.forge.iam.application.identity.EnsureIdentityService;
import io.saas.forge.iam.application.signing.JwtSigningService;
import io.saas.forge.iam.domain.authorization.PlatformRoleAssignmentRepository;
import io.saas.forge.iam.domain.identity.IdentityRepository;
import io.saas.forge.iam.domain.identity.PasswordSetupChallengeRepository;
import io.saas.forge.iam.domain.identity.PasswordSetupDeliveryRepository;
import io.saas.forge.iam.domain.identity.IdentityProvisioningRepository;
import io.saas.forge.iam.domain.outbox.OutboxEventRepository;
import io.saas.forge.iam.domain.client.OAuthClientRepository;
import io.saas.forge.iam.domain.session.AccessTokenIssuanceRepository;
import io.saas.forge.iam.domain.session.RefreshTokenFamilyRepository;
import io.saas.forge.iam.domain.session.RevocationFenceRepository;
import io.saas.forge.iam.domain.session.TenantContextSwitchRepository;
import io.saas.forge.iam.domain.session.UserSessionRevocationRepository;
import io.saas.forge.iam.domain.signing.SigningKeyRepository;
import io.saas.forge.iam.infrastructure.grpc.GrpcAccessibleMemberships;
import io.saas.forge.iam.infrastructure.security.RedisLoginProtection;
import io.saas.forge.iam.infrastructure.security.RedisRevocationIndex;
import io.saas.forge.iam.infrastructure.security.RedisPasswordRecoveryRateLimiter;
import io.saas.forge.iam.infrastructure.persistence.PasswordRecoveryRepository;
import io.saas.forge.iam.infrastructure.security.RedisRefreshRotationLease;
import io.saas.forge.iam.infrastructure.security.NimbusPresentedAccessTokenVerifier;
import io.saas.forge.iam.api.BrowserRequestSecurity;
import io.saas.forge.iam.infrastructure.security.ClasspathCompromisedPasswordChecker;
import io.saas.forge.iam.infrastructure.security.IamJwtVerificationKeyResolver;
import io.saas.forge.sdk.auth.ServiceAccessTokenAuthorizer;
import io.saas.forge.sdk.auth.ServiceAccessTokenRevocationChecker;
import io.saas.forge.sdk.auth.ServiceAccessTokenSignatureVerifier;
import io.saas.forge.sdk.auth.ServiceJwtVerificationKeyResolver;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import io.grpc.Channel;
import org.springframework.beans.factory.annotation.Qualifier;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
public class AuthenticationConfiguration {
    @Bean
    CurrentSessionQuery currentSessionQuery(
            SigningKeyRepository signingKeys, RevocationIndex revocations,
            IdentityRepository identities, PlatformRoleAuthorizationService roles, Clock clock,
            @Value("${security.jwt.issuer}") String issuer) {
        return new CurrentSessionQuery(
                new UserAccessTokenSignatureVerifier(new IamJwtVerificationKeyResolver(signingKeys),
                        clock, issuer, "saas.forge-api", Duration.ofSeconds(30)),
                revocations, identities, roles);
    }

    @Bean
    CurrentTenantContextQuery currentTenantContextQuery(
            SigningKeyRepository signingKeys, AccessibleMemberships memberships,
            RevocationIndex revocations, Clock clock,
            @Value("${security.jwt.issuer}") String issuer) {
        return new CurrentTenantContextQuery(
                new UserAccessTokenSignatureVerifier(
                        new IamJwtVerificationKeyResolver(signingKeys),
                        clock, issuer, "saas.forge-api", Duration.ofSeconds(30)), memberships, revocations);
    }

    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    SecureRandom authenticationSecureRandom() {
        return new SecureRandom();
    }

    @Bean
    UuidV7Generator uuidV7Generator(Clock clock, SecureRandom authenticationSecureRandom) {
        return new UuidV7Generator(clock, authenticationSecureRandom);
    }

    @Bean
    PasswordVerifier passwordVerifier() {
        return new PasswordVerifier();
    }

    @Bean
    PasswordSetupChallengeIssuer passwordSetupChallengeIssuer(SecureRandom authenticationSecureRandom) {
        return new PasswordSetupChallengeIssuer(authenticationSecureRandom);
    }

    @Bean
    PasswordPolicy passwordPolicy() {
        return new PasswordPolicy();
    }

    @Bean
    CompromisedPasswordChecker compromisedPasswordChecker(
            @Value("${saas.forge.environment:dev}") String environment) {
        return new ClasspathCompromisedPasswordChecker(environment);
    }

    @Bean
    LoginProtection loginProtection(
            StringRedisTemplate redis,
            @Value("${saas.forge.environment:dev}") String environment,
            @Value("${security.login-protection.failure-window:PT15M}") Duration failureWindow,
            @Value("${security.login-protection.maximum-failures:5}") int maximumFailures,
            @Value("${security.login-protection.lock-duration:PT15M}") Duration lockDuration) {
        return new RedisLoginProtection(redis, environment, failureWindow, maximumFailures, lockDuration);
    }

    @Bean
    UserAccessTokenIssuer userAccessTokenIssuer(
            JwtSigningService signingService,
            ObjectMapper objectMapper,
            UuidV7Generator uuidV7Generator,
            Clock clock,
            @Value("${security.jwt.issuer}") String issuer,
            @Value("${security.jwt.access-token-ttl:PT15M}") Duration ttl,
            UserTokenIssuanceFence issuanceFence) {
        return new UserAccessTokenIssuer(
                signingService, objectMapper, uuidV7Generator, clock, issuer, ttl, issuanceFence);
    }

    @Bean
    ServiceAccessTokenIssuer serviceAccessTokenIssuer(
            JwtSigningService signingService,
            ObjectMapper objectMapper,
            UuidV7Generator uuidV7Generator,
            Clock clock,
            @Value("${security.jwt.issuer}") String issuer,
            @Value("${security.jwt.service-access-token-ttl:PT5M}") Duration ttl) {
        return new ServiceAccessTokenIssuer(
                signingService, objectMapper, uuidV7Generator, clock, issuer, ttl);
    }

    @Bean
    ClientCredentialsTokenService clientCredentialsTokenService(
            OAuthClientRepository clients,
            ServiceAccessTokenIssuer tokens,
            RevocationIndex revocations,
            Clock clock) {
        return new ClientCredentialsTokenService(clients, tokens, revocations, clock);
    }

    @Bean
    ServiceJwtVerificationKeyResolver serviceJwtVerificationKeyResolver(SigningKeyRepository signingKeys) {
        return new IamJwtVerificationKeyResolver(signingKeys);
    }

    @Bean
    ServiceAccessTokenSignatureVerifier serviceAccessTokenSignatureVerifier(
            ServiceJwtVerificationKeyResolver keys,
            Clock clock,
            @Value("${security.jwt.issuer}") String issuer) {
        return new ServiceAccessTokenSignatureVerifier(
                keys, clock, issuer, "saas.forge-api", Duration.ofSeconds(30));
    }

    @Bean
    ServiceAccessTokenRevocationChecker serviceAccessTokenRevocationChecker(RevocationIndex revocations) {
        return revocations::isServiceTokenRevoked;
    }

    @Bean
    ServiceAccessTokenAuthorizer serviceAccessTokenAuthorizer(
            ServiceAccessTokenSignatureVerifier signatures,
            ServiceAccessTokenRevocationChecker revocations) {
        return new ServiceAccessTokenAuthorizer(signatures, revocations);
    }

    @Bean
    PlatformRoleAuthorizationService platformRoleAuthorizationService(
            PlatformRoleAssignmentRepository roles, Clock clock) {
        return new PlatformRoleAuthorizationService(roles, clock);
    }

    @Bean
    EnsureIdentityService ensureIdentityService(
            IdentityProvisioningRepository requests,
            IdentityRepository identities,
            Clock clock) {
        return new EnsureIdentityService(requests, identities, clock);
    }

    @Bean
    RefreshTokenIssuer refreshTokenIssuer(SecureRandom authenticationSecureRandom) {
        return new RefreshTokenIssuer(authenticationSecureRandom);
    }

    @Bean
    SessionStartedEventFactory sessionStartedEventFactory(
            ObjectMapper objectMapper,
            UuidV7Generator uuidV7Generator,
            @Value("${saas.forge.environment:dev}") String environment) {
        return new SessionStartedEventFactory(objectMapper, uuidV7Generator, environment);
    }

    @Bean
    PasswordChangedEventFactory passwordChangedEventFactory(
            ObjectMapper objectMapper,
            UuidV7Generator uuidV7Generator,
            @Value("${saas.forge.environment:dev}") String environment) {
        return new PasswordChangedEventFactory(objectMapper, uuidV7Generator, environment);
    }

    @Bean
    PasswordEstablishedEventFactory passwordEstablishedEventFactory(
            ObjectMapper objectMapper,
            UuidV7Generator uuidV7Generator,
            @Value("${saas.forge.environment:dev}") String environment) {
        return new PasswordEstablishedEventFactory(objectMapper, uuidV7Generator, environment);
    }

    @Bean
    PasswordSetupDeliveredEventFactory passwordSetupDeliveredEventFactory(
            ObjectMapper objectMapper,
            UuidV7Generator uuidV7Generator,
            @Value("${saas.forge.environment:dev}") String environment) {
        return new PasswordSetupDeliveredEventFactory(objectMapper, uuidV7Generator, environment);
    }

    @Bean
    SessionRevokedEventFactory sessionRevokedEventFactory(
            ObjectMapper objectMapper,
            UuidV7Generator uuidV7Generator,
            @Value("${saas.forge.environment:dev}") String environment) {
        return new SessionRevokedEventFactory(objectMapper, uuidV7Generator, environment);
    }

    @Bean
    RefreshReplayDetectedEventFactory refreshReplayDetectedEventFactory(
            ObjectMapper objectMapper,
            UuidV7Generator uuidV7Generator,
            @Value("${saas.forge.environment:dev}") String environment) {
        return new RefreshReplayDetectedEventFactory(objectMapper, uuidV7Generator, environment);
    }

    @Bean
    TenantContextSwitchedEventFactory tenantContextSwitchedEventFactory(
            ObjectMapper objectMapper,
            UuidV7Generator uuidV7Generator,
            @Value("${saas.forge.environment:dev}") String environment) {
        return new TenantContextSwitchedEventFactory(objectMapper, uuidV7Generator, environment);
    }

    @Bean
    RefreshRotationLease refreshRotationLease(
            StringRedisTemplate redis,
            @Value("${saas.forge.environment:dev}") String environment,
            @Value("${security.refresh.rotation-lease:PT5S}") Duration leaseDuration) {
        return new RedisRefreshRotationLease(redis, environment, leaseDuration);
    }

    @Bean
    RefreshRotationTransaction refreshRotationTransaction(
            RefreshTokenFamilyRepository families,
            TenantContextSwitchRepository contextSwitches,
            AccessTokenIssuanceRepository issuances,
            RevocationIndex revocationIndex,
            OutboxEventRepository outboxEvents,
            RefreshReplayDetectedEventFactory replayEventFactory,
            SessionRevokedEventFactory revokedEventFactory,
            @Value("${security.refresh.recovery-window:PT10S}") Duration recoveryWindow,
            UserTokenIssuanceFence issuanceFence) {
        return new RefreshRotationTransaction(
                families, contextSwitches, issuances, revocationIndex, outboxEvents,
                replayEventFactory, revokedEventFactory, recoveryWindow, issuanceFence);
    }

    @Bean
    RevocationIndex revocationIndex(
            StringRedisTemplate redis,
            @Value("${saas.forge.environment:dev}") String environment) {
        return new RedisRevocationIndex(redis, environment);
    }

    @Bean
    RevocationFenceOperations revocationFenceService(
            RevocationFenceRepository fences, RevocationIndex index, Clock clock) {
        return new RevocationFenceService(fences, index, clock);
    }

    @Bean
    UserSessionsRevokedEventFactory userSessionsRevokedEventFactory(
            ObjectMapper objectMapper,
            UuidV7Generator uuidV7Generator,
            @Value("${saas.forge.environment:dev}") String environment) {
        return new UserSessionsRevokedEventFactory(objectMapper, uuidV7Generator, environment);
    }

    @Bean
    UserSessionRevocationRecoveryPolicy userSessionRevocationRecoveryPolicy(
            @Value("${saas.forge.iam.session-revocation.batch-size:1}") int batchSize,
            @Value("${saas.forge.iam.session-revocation.lease-duration:PT30S}") Duration leaseDuration,
            @Value("${saas.forge.iam.session-revocation.retry-delay:PT1S}") Duration retryDelay,
            @Value("${saas.forge.iam.session-revocation.maximum-attempts:10}") int maximumAttempts) {
        return new UserSessionRevocationRecoveryPolicy(
                batchSize, leaseDuration, retryDelay, maximumAttempts);
    }

    @Bean
    UserSessionRevocationService userSessionRevocationService(
            RevocationFenceRepository fences,
            UserSessionRevocationRepository workflows,
            RevocationIndex index,
            UserSessionRevocationTransaction transaction,
            UserSessionRevocationRecoveryPolicy policy,
            Clock clock) {
        return new UserSessionRevocationService(
                fences, workflows, index, transaction, policy,
                java.lang.management.ManagementFactory.getRuntimeMXBean().getName(), clock);
    }

    @Bean
    UserSessionRevocationTransaction userSessionRevocationTransaction(
            UserSessionRevocationRepository workflows,
            OutboxEventRepository outbox,
            UserSessionsRevokedEventFactory events,
            RevocationFenceOperations fences) {
        return new UserSessionRevocationTransaction(workflows, outbox, events, fences);
    }

    @Bean
    UserSessionRevocationWorker userSessionRevocationWorker(UserSessionRevocationService service) {
        return new UserSessionRevocationWorker(service);
    }

    @Bean
    RedisPasswordRecoveryRateLimiter passwordRecoveryRateLimiter(StringRedisTemplate redis,
            @Value("${saas.forge.environment:dev}") String environment,
            @Value("${security.password-recovery.maximum-requests:3}") int maximumRequests,
            @Value("${security.password-recovery.request-window:PT1H}") Duration requestWindow) {
        return new RedisPasswordRecoveryRateLimiter(redis, environment, maximumRequests, requestWindow);
    }

    @Bean
    PasswordRecoveredEventFactory passwordRecoveredEventFactory(ObjectMapper json, UuidV7Generator ids,
            @Value("${saas.forge.environment:dev}") String environment) {
        return new PasswordRecoveredEventFactory(json, ids, environment);
    }

    @Bean
    PasswordRecoveryService passwordRecoveryService(PasswordRecoveryRepository challenges,
            IdentityRepository identities, RevocationFenceRepository fences, PasswordSetupChallengeIssuer tokens,
            PasswordRecoveryMailer mailer, RedisPasswordRecoveryRateLimiter rateLimiter,
            PasswordPolicy policy, CompromisedPasswordChecker compromised, PasswordVerifier verifier,
            UserSessionRevocationTransaction revocationTransaction, UserSessionRevocationService revocations,
            UserSessionRevocationRepository workflows, OutboxEventRepository outbox,
            PasswordRecoveredEventFactory events, PlatformTransactionManager manager, Clock clock) {
        return new PasswordRecoveryService(challenges, identities, fences, tokens, mailer, rateLimiter,
                policy, compromised, verifier, revocationTransaction, revocations, workflows, outbox,
                events, new TransactionTemplate(manager), clock);
    }

    @Bean
    PasswordRecoveryWorker passwordRecoveryWorker(PasswordRecoveryService service) {
        return new PasswordRecoveryWorker(service);
    }

    @Bean
    RevocationIndexRecovery revocationIndexRecovery(
            RevocationIndex index,
            AccessTokenIssuanceRepository issuances,
            RevocationFenceRepository fences,
            OAuthClientRepository clients,
            Clock clock) {
        return new RevocationIndexRecovery(index, issuances, fences, clients, clock);
    }

    @Bean
    @ConditionalOnMissingBean(PresentedAccessTokenVerifier.class)
    PresentedAccessTokenVerifier presentedAccessTokenVerifier(
            SigningKeyRepository signingKeys,
            Clock clock,
            @Value("${security.jwt.issuer}") String issuer) {
        return new NimbusPresentedAccessTokenVerifier(signingKeys, clock, issuer);
    }

    @Bean
    LogoutTransaction logoutTransaction(
            RefreshTokenFamilyRepository families,
            AccessTokenIssuanceRepository issuances,
            OutboxEventRepository outboxEvents,
            SessionRevokedEventFactory eventFactory) {
        return new LogoutTransaction(families, issuances, outboxEvents, eventFactory);
    }

    @Bean
    LogoutService logoutService(
            PresentedAccessTokenVerifier accessTokens,
            AccessTokenIssuanceRepository issuances,
            RefreshTokenIssuer refreshTokens,
            RefreshTokenFamilyRepository refreshTokenFamilies,
            RevocationIndex revocationIndex,
            LogoutTransaction transaction,
            Clock clock) {
        return new LogoutService(
                accessTokens, issuances, refreshTokens, refreshTokenFamilies, revocationIndex, transaction, clock);
    }

    @Bean
    LoginSessionService loginSessionService(
            PlatformRoleAssignmentRepository platformRoles,
            RefreshTokenFamilyRepository refreshTokenFamilies,
            AccessTokenIssuanceRepository accessTokenIssuances,
            OutboxEventRepository outboxEvents,
            SessionStartedEventFactory eventFactory,
            UserTokenIssuanceFence issuanceFence) {
        return new LoginSessionService(
                platformRoles, refreshTokenFamilies, accessTokenIssuances, outboxEvents, eventFactory, issuanceFence);
    }

    @Bean
    @ConditionalOnMissingBean(AccessibleMemberships.class)
    AccessibleMemberships accessibleMemberships(
            @Qualifier("tenantAccessMembershipChannel") Channel membershipChannel) {
        return new GrpcAccessibleMemberships(
                AccessibleMembershipQueryServiceGrpc.newBlockingStub(membershipChannel));
    }

    @Bean
    PasswordLoginService passwordLoginService(
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
        return new PasswordLoginService(identities, platformRoles, accessibleMemberships, loginProtection, passwordVerifier,
                accessTokenIssuer, refreshTokenIssuer, refreshTokenFamilies, sessionService, clock);
    }

    @Bean
    ContextSelectionService contextSelectionService(
            AccessibleMemberships accessibleMemberships,
            RefreshTokenFamilyRepository refreshTokenFamilies,
            UserAccessTokenIssuer accessTokenIssuer,
            RefreshTokenIssuer refreshTokenIssuer,
            LoginSessionService sessionService,
            Clock clock) {
        return new ContextSelectionService(
                accessibleMemberships, refreshTokenFamilies, accessTokenIssuer,
                refreshTokenIssuer, sessionService, clock);
    }

    @Bean
    RefreshSessionService refreshSessionService(
            PlatformRoleAssignmentRepository platformRoles,
            AccessibleMemberships accessibleMemberships,
            RefreshTokenFamilyRepository refreshTokenFamilies,
            TenantContextSwitchRepository contextSwitches,
            MembershipValidation membershipValidation,
            UserAccessTokenIssuer accessTokenIssuer,
            RefreshTokenIssuer refreshTokenIssuer,
            LoginSessionService sessionService,
            RefreshRotationLease rotationLease,
            RefreshRotationTransaction rotationTransaction,
            TenantContextSwitchTransaction contextSwitchTransaction,
            Clock clock) {
        return new RefreshSessionService(
                platformRoles, accessibleMemberships, refreshTokenFamilies, contextSwitches, membershipValidation,
                accessTokenIssuer, refreshTokenIssuer, sessionService, rotationLease, rotationTransaction,
                contextSwitchTransaction, clock);
    }

    @Bean
    BrowserRequestSecurity browserRequestSecurity(
            @Value("${browser.rootDomain}") String rootDomain) {
        return new BrowserRequestSecurity(rootDomain);
    }

    @Bean
    TenantContextSwitchTransaction tenantContextSwitchTransaction(
            TenantContextSwitchRepository workflows,
            RefreshTokenFamilyRepository families,
            AccessTokenIssuanceRepository issuances,
            RevocationIndex revocationIndex,
            OutboxEventRepository outboxEvents,
            TenantContextSwitchedEventFactory eventFactory,
            UserTokenIssuanceFence issuanceFence) {
        return new TenantContextSwitchTransaction(
                workflows, families, issuances, revocationIndex, outboxEvents, eventFactory, issuanceFence);
    }

    @Bean
    TenantContextSwitchRecoveryPolicy tenantContextSwitchRecoveryPolicy(
            @Value("${saas.forge.iam.tenant-context-switch.lease-duration:PT30S}") Duration leaseDuration,
            @Value("${saas.forge.iam.tenant-context-switch.initial-backoff:PT1S}") Duration initialBackoff,
            @Value("${saas.forge.iam.tenant-context-switch.maximum-backoff:PT1M}") Duration maximumBackoff,
            @Value("${saas.forge.iam.tenant-context-switch.maximum-attempts:10}") int maximumAttempts) {
        return new TenantContextSwitchRecoveryPolicy(
                leaseDuration, initialBackoff, maximumBackoff, maximumAttempts);
    }

    @Bean
    TenantContextSwitchService tenantContextSwitchService(
            RefreshTokenFamilyRepository families,
            TenantContextSwitchRepository workflows,
            MembershipValidation memberships,
            RefreshTokenIssuer refreshTokens,
            TenantContextSwitchTransaction transaction,
            TenantContextSwitchRecoveryPolicy recoveryPolicy,
            Clock clock) {
        return new TenantContextSwitchService(
                families, workflows, memberships, refreshTokens, transaction, recoveryPolicy,
                java.lang.management.ManagementFactory.getRuntimeMXBean().getName(), clock);
    }

    @Bean
    TenantContextSwitchWorker tenantContextSwitchWorker(TenantContextSwitchService service) {
        return new TenantContextSwitchWorker(service);
    }

    @Bean
    InitialPasswordChangeService initialPasswordChangeService(
            IdentityRepository identities,
            RefreshTokenFamilyRepository refreshTokenFamilies,
            RefreshTokenIssuer refreshTokenIssuer,
            PasswordPolicy passwordPolicy,
            CompromisedPasswordChecker compromisedPasswords,
            PasswordVerifier passwordVerifier,
            OutboxEventRepository outboxEvents,
            PasswordChangedEventFactory eventFactory,
            Clock clock) {
        return new InitialPasswordChangeService(
                identities, refreshTokenFamilies, refreshTokenIssuer, passwordPolicy, compromisedPasswords,
                passwordVerifier, outboxEvents, eventFactory, clock);
    }

    @Bean
    PasswordSetupService passwordSetupService(
            PasswordSetupChallengeRepository challenges,
            IdentityRepository identities,
            PasswordSetupChallengeIssuer challengeIssuer,
            PasswordPolicy passwordPolicy,
            CompromisedPasswordChecker compromisedPasswords,
            PasswordVerifier passwordVerifier,
            OutboxEventRepository outboxEvents,
            PasswordEstablishedEventFactory eventFactory,
            Clock clock) {
        return new PasswordSetupService(
                challenges, identities, challengeIssuer, passwordPolicy, compromisedPasswords,
                passwordVerifier, outboxEvents, eventFactory, clock);
    }

    @Bean
    PasswordSetupDeliveryTransaction passwordSetupDeliveryTransaction(
            PasswordSetupDeliveryRepository deliveries,
            IdentityRepository identities,
            PasswordSetupService passwordSetups,
            OutboxEventRepository outboxEvents,
            PasswordSetupDeliveredEventFactory eventFactory,
            Clock clock) {
        return new PasswordSetupDeliveryTransaction(
                deliveries, identities, passwordSetups, outboxEvents, eventFactory, clock);
    }

    @Bean
    PasswordSetupDeliveryService passwordSetupDeliveryService(
            PasswordSetupDeliveryTransaction transaction,
            PasswordSetupMailer mailer,
            PasswordSetupMailConfiguration.PasswordSetupMailSettings mailSettings) {
        return new PasswordSetupDeliveryService(transaction, mailer, mailSettings.pageUri());
    }
}
