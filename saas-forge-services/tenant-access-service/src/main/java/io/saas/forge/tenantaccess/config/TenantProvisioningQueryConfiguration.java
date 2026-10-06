package io.saas.forge.tenantaccess.config;

import io.saas.forge.sdk.auth.ServiceAccessTokenAuthorizer;
import io.saas.forge.sdk.auth.ServiceAccessTokenRevocationChecker;
import io.saas.forge.sdk.auth.ServiceAccessTokenSignatureVerifier;
import io.saas.forge.tenantaccess.application.tenant.InitialSubscriptionEligibilityService;
import io.saas.forge.tenantaccess.domain.tenant.TenantRepository;
import io.saas.forge.tenantaccess.infrastructure.security.IamJwksKeyResolver;
import io.saas.forge.tenantaccess.infrastructure.security.IamServiceClientId;
import io.saas.forge.tenantaccess.infrastructure.security.MembershipValidationClients;
import io.saas.forge.tenantaccess.infrastructure.security.RedisServiceAccessTokenRevocationChecker;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.HashSet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.client.RestClient;

@Configuration
public class TenantProvisioningQueryConfiguration {
    @Bean
    MembershipValidationClients membershipValidationClients(
            IamServiceClientId iam,
            @Value("${saas.forge.tenant-access.remote-delivery-service-client-id-file:}") String remoteFile) {
        var clients = new HashSet<UUID>();
        clients.add(iam.value());
        if (!remoteFile.isBlank()) {
            try {
                String value = Files.readString(Path.of(remoteFile)).stripTrailing();
                UUID id = UUID.fromString(value);
                if (!id.toString().equals(value) || id.equals(iam.value())) throw new IllegalArgumentException();
                clients.add(id);
            } catch (IOException | IllegalArgumentException invalid) {
                throw new IllegalStateException("Remote Delivery Service Client ID 文件不可读或内容不合法", invalid);
            }
        }
        return new MembershipValidationClients(clients);
    }
    @Bean
    InitialSubscriptionEligibilityService initialSubscriptionEligibilityService(
            TenantRepository tenants, Clock clock) {
        return new InitialSubscriptionEligibilityService(tenants, clock);
    }

    @Bean
    ServiceAccessTokenSignatureVerifier tenantAccessServiceAccessTokenSignatureVerifier(
            RestClient tenantAccessIamRestClient,
            Clock clock,
            @Value("${security.jwt.issuer}") String issuer) {
        return new ServiceAccessTokenSignatureVerifier(
                new IamJwksKeyResolver(tenantAccessIamRestClient),
                clock, issuer, "saas.forge-api", Duration.ofSeconds(30));
    }

    @Bean
    ServiceAccessTokenRevocationChecker tenantAccessServiceAccessTokenRevocationChecker(
            StringRedisTemplate redis,
            @Value("${saas.forge.environment:dev}") String environment) {
        return new RedisServiceAccessTokenRevocationChecker(redis, environment);
    }

    @Bean
    ServiceAccessTokenAuthorizer tenantAccessServiceAccessTokenAuthorizer(
            ServiceAccessTokenSignatureVerifier signatures,
            ServiceAccessTokenRevocationChecker revocations) {
        return new ServiceAccessTokenAuthorizer(signatures, revocations);
    }

    @Bean
    IamServiceClientId iamServiceClientId(
            @Value("${saas.forge.tenant-access.iam-service-client-id-file}") String clientIdFile) {
        try {
            String value = Files.readString(Path.of(clientIdFile)).stripTrailing();
            UUID clientId = UUID.fromString(value);
            if (!clientId.toString().equals(value)) {
                throw new IllegalStateException("IAM Service Client ID 必须是规范 UUIDv7");
            }
            return new IamServiceClientId(clientId);
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalStateException("IAM Service Client ID 文件不可读或内容不合法", exception);
        }
    }
}
