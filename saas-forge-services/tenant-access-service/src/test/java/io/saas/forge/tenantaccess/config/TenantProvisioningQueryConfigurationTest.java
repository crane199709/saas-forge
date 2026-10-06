package io.saas.forge.tenantaccess.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.saas.forge.sdk.auth.ServiceAccessTokenRevocationChecker;
import io.saas.forge.sdk.auth.ServiceAccessTokenSignatureVerifier;
import io.saas.forge.tenantaccess.domain.tenant.Tenant;
import io.saas.forge.tenantaccess.domain.tenant.TenantRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.client.RestClient;

class TenantProvisioningQueryConfigurationTest {
    private static final UUID IAM_SERVICE_CLIENT_ID =
            UUID.fromString("019535d9-0001-7000-8000-000000000001");

    @TempDir
    Path directory;

    private final TenantProvisioningQueryConfiguration configuration =
            new TenantProvisioningQueryConfiguration();

    @Test
    void wiresEligibilityAndTokenAuthorization() {
        assertNotNull(configuration.initialSubscriptionEligibilityService(
                emptyTenantRepository(), Clock.systemUTC()));
        ServiceAccessTokenSignatureVerifier signatures =
                configuration.tenantAccessServiceAccessTokenSignatureVerifier(
                        RestClient.create(), Clock.systemUTC(), "https://iam.test.saas.forge.invalid");
        ServiceAccessTokenRevocationChecker revocations =
                configuration.tenantAccessServiceAccessTokenRevocationChecker(
                        org.mockito.Mockito.mock(StringRedisTemplate.class), "test");
        assertNotNull(configuration.tenantAccessServiceAccessTokenAuthorizer(signatures, revocations));
    }

    @Test
    void remoteMembershipReaderIsOptionalExactAndFailClosed() throws Exception {
        var iam = new io.saas.forge.tenantaccess.infrastructure.security.IamServiceClientId(IAM_SERVICE_CLIENT_ID);
        assertEquals(java.util.Set.of(IAM_SERVICE_CLIENT_ID), configuration.membershipValidationClients(iam, "").values());
        UUID remote = UUID.fromString("019535d9-0001-7000-8000-000000000002");
        Path file = Files.writeString(directory.resolve("remote-client-id"), remote+"\n");
        assertEquals(java.util.Set.of(IAM_SERVICE_CLIENT_ID,remote),
                configuration.membershipValidationClients(iam,file.toString()).values());
        assertThrows(IllegalStateException.class, () -> configuration.membershipValidationClients(iam,directory.resolve("missing").toString()));
        Files.writeString(file,IAM_SERVICE_CLIENT_ID.toString());
        assertThrows(IllegalStateException.class, () -> configuration.membershipValidationClients(iam,file.toString()));
        Files.writeString(file,UUID.randomUUID().toString());
        assertThrows(IllegalArgumentException.class, () -> configuration.membershipValidationClients(iam,file.toString()));
    }

    @Test
    void readsCanonicalIamServiceClientIdFromSecretFile() throws Exception {
        Path clientIdFile = Files.writeString(directory.resolve("iam-client-id"), IAM_SERVICE_CLIENT_ID + "\n");

        assertEquals(IAM_SERVICE_CLIENT_ID, configuration.iamServiceClientId(clientIdFile.toString()).value());
    }

    @Test
    void rejectsMissingMalformedAndNonV7ClientIdFiles() throws Exception {
        Path malformed = Files.writeString(directory.resolve("malformed-client-id"), "not-a-uuid");
        Path nonV7 = Files.writeString(directory.resolve("non-v7-client-id"), UUID.randomUUID().toString());

        assertThrows(IllegalStateException.class,
                () -> configuration.iamServiceClientId(directory.resolve("missing-client-id").toString()));
        assertThrows(IllegalStateException.class,
                () -> configuration.iamServiceClientId(malformed.toString()));
        assertThrows(IllegalStateException.class,
                () -> configuration.iamServiceClientId(nonV7.toString()));
    }

    private static TenantRepository emptyTenantRepository() {
        return new TenantRepository() {
            @Override
            public void setOperationTarget(UUID tenantId) {}

            @Override
            public void create(Tenant tenant) {}

            @Override
            public Optional<Tenant> findById(UUID tenantId) {
                return Optional.empty();
            }
        };
    }
}
