package io.saas.forge.tenantaccess.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.saas.forge.tenantaccess.application.brand.BrandAssetCleanupService;
import io.saas.forge.tenantaccess.application.brand.BrandUploadRecoveryService;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

class BrandAssetConfigurationTest {
    @TempDir Path secrets;

    @Test
    void readsOnlyCredentialFilesAndRefusesBlankS3Identity() throws IOException {
        Path access = secrets.resolve("access");
        Path secret = secrets.resolve("secret");
        Files.writeString(access, "  acceptance-access\n");
        Files.writeString(secret, "  acceptance-secret\n");
        var configuration = new BrandAssetConfiguration();
        try (var client = configuration.brandS3Client(URI.create("http://127.0.0.1:9000"),
                "us-east-1", access, secret)) {
            var credentials = ((StaticCredentialsProvider) client.serviceClientConfiguration()
                    .credentialsProvider()).resolveCredentials();
            assertEquals("acceptance-access", credentials.accessKeyId());
            assertEquals("acceptance-secret", credentials.secretAccessKey());
            assertEquals(URI.create("http://127.0.0.1:9000"),
                    client.serviceClientConfiguration().endpointOverride().orElseThrow());
        }
        Files.writeString(secret, " \n");
        assertThrows(IllegalArgumentException.class, () -> configuration.brandS3Client(
                URI.create("http://127.0.0.1:9000"), "us-east-1", access, secret));
        Files.delete(access);
        assertThrows(IOException.class, () -> configuration.brandS3Client(
                URI.create("http://127.0.0.1:9000"), "us-east-1", access, secret));
    }

    @Test
    void uploadRecoveryWorkerStopsAtNoMoreWorkAndDefersFailures() {
        var recovery = mock(BrandUploadRecoveryService.class);
        when(recovery.cleanNext()).thenReturn(true, true, false);
        new BrandAssetConfiguration.BrandUploadRecoveryWorker(recovery).recover();
        verify(recovery, times(3)).cleanNext();

        var unavailable = mock(BrandUploadRecoveryService.class);
        when(unavailable.cleanNext()).thenThrow(new IllegalStateException("object store offline"));
        new BrandAssetConfiguration.BrandUploadRecoveryWorker(unavailable).recover();
        verify(unavailable).cleanNext();
    }

    @Test
    void assetCleanupWorkerProcessesBoundedBatchesAndRetriesLaterOnFailure() {
        var cleanup = mock(BrandAssetCleanupService.class);
        when(cleanup.cleanNext()).thenReturn(true);
        new BrandAssetConfiguration.BrandAssetCleanupWorker(cleanup).clean();
        verify(cleanup, times(100)).cleanNext();

        var unavailable = mock(BrandAssetCleanupService.class);
        when(unavailable.cleanNext()).thenThrow(new IllegalStateException("object store offline"));
        new BrandAssetConfiguration.BrandAssetCleanupWorker(unavailable).clean();
        verify(unavailable).cleanNext();
    }
}
