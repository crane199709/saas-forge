package io.saas.forge.iambootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.saas.forge.iam.application.bootstrap.ReservedServiceClient;
import io.saas.forge.iam.application.bootstrap.ReservedServiceClientBootstrapInput;
import io.saas.forge.iam.application.bootstrap.ReservedServiceClientBootstrapResult;
import io.saas.forge.iam.application.bootstrap.ReservedServiceClientBootstrapService;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.ApplicationArguments;

class ReservedServiceClientBootstrapRunnerTest {
    private static final String IAM_ID = "0198c9d5-0f25-7b21-8d67-31c8652d4c8f";
    private static final String TENANT_ACCESS_ID = "0198c9d5-0f25-7b21-8d67-31c8652d4c90";
    private static final String ENTITLEMENT_ID = "0198c9d5-0f25-7b21-8d67-31c8652d4c91";

    @Test
    void passesOnlyTheThreeReservedServiceCredentialsToBootstrap() {
        ReservedServiceClientBootstrapService service = mock(ReservedServiceClientBootstrapService.class);
        SecretTextFileReader reader = mock(SecretTextFileReader.class);
        Path iamId = Path.of("iam-id");
        Path iamSecret = Path.of("iam-secret");
        Path tenantId = Path.of("tenant-id");
        Path tenantSecret = Path.of("tenant-secret");
        Path entitlementId = Path.of("entitlement-id");
        Path entitlementSecret = Path.of("entitlement-secret");
        when(reader.read(iamId, 36)).thenReturn(IAM_ID);
        when(reader.read(iamSecret, 43)).thenReturn("iam-service-secret");
        when(reader.read(tenantId, 36)).thenReturn(TENANT_ACCESS_ID);
        when(reader.read(tenantSecret, 43)).thenReturn("tenant-access-secret");
        when(reader.read(entitlementId, 36)).thenReturn(ENTITLEMENT_ID);
        when(reader.read(entitlementSecret, 43)).thenReturn("entitlement-secret");
        when(service.bootstrap(anyList())).thenReturn(new ReservedServiceClientBootstrapResult(Map.of(
                ReservedServiceClient.IAM, result(IAM_ID),
                ReservedServiceClient.TENANT_ACCESS, result(TENANT_ACCESS_ID),
                ReservedServiceClient.ENTITLEMENT, result(ENTITLEMENT_ID))));

        new ReservedServiceClientBootstrapRunner(
                service, reader, iamId, iamSecret, tenantId, tenantSecret, entitlementId, entitlementSecret)
                .run(mock(ApplicationArguments.class));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ReservedServiceClientBootstrapInput>> inputs = ArgumentCaptor.forClass(List.class);
        verify(service).bootstrap(inputs.capture());
        assertEquals(List.of(
                        ReservedServiceClient.IAM,
                        ReservedServiceClient.TENANT_ACCESS,
                        ReservedServiceClient.ENTITLEMENT),
                inputs.getValue().stream().map(ReservedServiceClientBootstrapInput::service).toList());
    }

    @Test
    void passesRemoteDeliveryCredentialsOnlyWhenBothFilesAreConfigured() {
        var service = mock(ReservedServiceClientBootstrapService.class);
        var reader = mock(SecretTextFileReader.class);
        var id = Path.of("id");
        var secret = Path.of("secret");
        when(reader.read(id, 36)).thenReturn(IAM_ID);
        when(reader.read(secret, 43)).thenReturn("restricted-secret-file-value");
        when(service.bootstrap(anyList())).thenReturn(new ReservedServiceClientBootstrapResult(Map.of()));
        new ReservedServiceClientBootstrapRunner(service, reader, id, secret, id, secret, id, secret, id, secret)
                .run(mock(ApplicationArguments.class));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ReservedServiceClientBootstrapInput>> inputs = ArgumentCaptor.forClass(List.class);
        verify(service).bootstrap(inputs.capture());
        assertEquals(List.of(ReservedServiceClient.IAM, ReservedServiceClient.TENANT_ACCESS,
                ReservedServiceClient.ENTITLEMENT, ReservedServiceClient.REMOTE_DELIVERY),
                inputs.getValue().stream().map(ReservedServiceClientBootstrapInput::service).toList());
        assertThrows(IllegalArgumentException.class, () -> new ReservedServiceClientBootstrapRunner(
                service, reader, id, secret, id, secret, id, secret, id, null));
        assertThrows(IllegalArgumentException.class, () -> new ReservedServiceClientBootstrapRunner(
                service, reader, id, secret, id, secret, id, secret, null, secret));
    }

    @Test
    void rejectsNonUuidV7ClientIdBeforeBootstrap() {
        ReservedServiceClientBootstrapService service = mock(ReservedServiceClientBootstrapService.class);
        SecretTextFileReader reader = mock(SecretTextFileReader.class);
        Path invalidId = Path.of("invalid-id");
        when(reader.read(invalidId, 36)).thenReturn(UUID.randomUUID().toString());
        ReservedServiceClientBootstrapRunner runner = new ReservedServiceClientBootstrapRunner(
                service, reader, invalidId, Path.of("iam-secret"), Path.of("tenant-id"),
                Path.of("tenant-secret"), Path.of("entitlement-id"), Path.of("entitlement-secret"));

        assertThrows(IllegalArgumentException.class, () -> runner.run(mock(ApplicationArguments.class)));
    }

    @Test
    void traceIdGeneratorRetriesAnAllZeroValue() {
        SecureRandom random = mock(SecureRandom.class);
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            byte[] value = invocation.getArgument(0);
            if (calls.incrementAndGet() == 2) {
                value[15] = 1;
            }
            return null;
        }).when(random).nextBytes(org.mockito.ArgumentMatchers.any(byte[].class));

        assertEquals("00000000000000000000000000000001", new TraceIdGenerator(random).next());
        assertEquals(2, calls.get());
    }

    private static ReservedServiceClientBootstrapResult.ClientResult result(String clientId) {
        return new ReservedServiceClientBootstrapResult.ClientResult(
                UUID.fromString(clientId), ReservedServiceClientBootstrapResult.Outcome.INITIALIZED);
    }
}
