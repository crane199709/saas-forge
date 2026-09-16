package io.saas.forge.iam.config;

import io.saas.forge.iam.application.authentication.RevocationIndex;
import io.saas.forge.iam.application.authentication.UuidV7Generator;
import io.saas.forge.iam.application.client.ClientSecretIssuer;
import io.saas.forge.iam.application.client.ClientSecretIssuanceRecoveredEventFactory;
import io.saas.forge.iam.application.client.ClientSecretRotatedEventFactory;
import io.saas.forge.iam.application.client.OAuthClientCreatedEventFactory;
import io.saas.forge.iam.application.client.OAuthClientManagementAuthorizer;
import io.saas.forge.iam.application.client.OAuthClientManagementService;
import io.saas.forge.iam.application.client.OAuthClientRevokedEventFactory;
import io.saas.forge.iam.domain.authorization.PlatformRoleAssignmentRepository;
import io.saas.forge.iam.domain.client.OAuthClientManagementOperationRepository;
import io.saas.forge.iam.domain.client.OAuthClientRepository;
import io.saas.forge.iam.domain.outbox.OutboxEventRepository;
import io.saas.forge.iam.domain.signing.SigningKeyRepository;
import io.saas.forge.iam.infrastructure.security.IamJwtVerificationKeyResolver;
import io.saas.forge.sdk.auth.UserAccessTokenSignatureVerifier;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class OAuthClientManagementConfiguration {
    @Bean
    ClientSecretIssuer clientSecretIssuer(SecureRandom authenticationSecureRandom) {
        return new ClientSecretIssuer(authenticationSecureRandom);
    }

    @Bean
    OAuthClientCreatedEventFactory oauthClientCreatedEventFactory(
            ObjectMapper objectMapper,
            UuidV7Generator ids,
            @Value("${saas.forge.environment:dev}") String environment) {
        return new OAuthClientCreatedEventFactory(objectMapper, ids, environment);
    }

    @Bean
    ClientSecretRotatedEventFactory clientSecretRotatedEventFactory(
            ObjectMapper objectMapper,
            UuidV7Generator ids,
            @Value("${saas.forge.environment:dev}") String environment) {
        return new ClientSecretRotatedEventFactory(objectMapper, ids, environment);
    }

    @Bean
    ClientSecretIssuanceRecoveredEventFactory clientSecretIssuanceRecoveredEventFactory(
            ObjectMapper objectMapper,
            UuidV7Generator ids,
            @Value("${saas.forge.environment:dev}") String environment) {
        return new ClientSecretIssuanceRecoveredEventFactory(objectMapper, ids, environment);
    }

    @Bean
    OAuthClientRevokedEventFactory oauthClientRevokedEventFactory(
            ObjectMapper objectMapper,
            UuidV7Generator ids,
            @Value("${saas.forge.environment:dev}") String environment) {
        return new OAuthClientRevokedEventFactory(objectMapper, ids, environment);
    }

    @Bean
    OAuthClientManagementService oauthClientManagementService(
            OAuthClientRepository clients,
            OAuthClientManagementOperationRepository operations,
            OutboxEventRepository outbox,
            OAuthClientCreatedEventFactory events,
            ClientSecretRotatedEventFactory rotationEvents,
            ClientSecretIssuanceRecoveredEventFactory recoveryEvents,
            OAuthClientRevokedEventFactory revocationEvents,
            RevocationIndex revocations,
            ClientSecretIssuer secrets,
            UuidV7Generator ids,
            Clock clock) {
        return new OAuthClientManagementService(
                clients, operations, outbox, events, rotationEvents, recoveryEvents, revocationEvents,
                revocations, secrets, ids, clock);
    }

    @Bean
    OAuthClientManagementAuthorizer oauthClientManagementAuthorizer(
            SigningKeyRepository signingKeys,
            RevocationIndex revocations,
            PlatformRoleAssignmentRepository roles,
            Clock clock,
            @Value("${security.jwt.issuer}") String issuer,
            @Value("${security.browser.console-enabled:false}") boolean consoleEnabled) {
        UserAccessTokenSignatureVerifier signatures = new UserAccessTokenSignatureVerifier(
                new IamJwtVerificationKeyResolver(signingKeys), clock, issuer,
                "saas.forge-api", Duration.ofSeconds(30), consoleEnabled);
        return new OAuthClientManagementAuthorizer(signatures, revocations, roles, clock);
    }
}
