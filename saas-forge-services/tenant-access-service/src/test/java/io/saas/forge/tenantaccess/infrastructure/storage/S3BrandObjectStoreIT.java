package io.saas.forge.tenantaccess.infrastructure.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.S3Exception;

@Testcontainers
class S3BrandObjectStoreIT {
    @Container
    static final GenericContainer<?> silo = new GenericContainer<>(
            DockerImageName.parse("docker.io/pgsty/silo:RELEASE.2026-09-16T00-00-00Z"))
            .withEnv("MINIO_ROOT_USER", "brand-test-root")
            .withEnv("MINIO_ROOT_PASSWORD", "brand-test-secret-12345")
            .withCommand("server", "/data")
            .withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000));

    @Test
    void restrictedIdentityWritesReadsAndDeletesExactBrandObjectWithoutListing() throws Exception {
        try (S3Client client = S3Client.builder()
                .endpointOverride(URI.create("http://" + silo.getHost() + ":" + silo.getMappedPort(9000)))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("brand-test-root", "brand-test-secret-12345")))
                .forcePathStyle(true).build()) {
            client.createBucket(CreateBucketRequest.builder().bucket("brand-assets-test").build());
            silo.copyFileToContainer(Transferable.of("""
                    {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                    "Action":["s3:PutObject","s3:GetObject","s3:DeleteObject"],
                    "Resource":["arn:aws:s3:::brand-assets-test/*"]}]}
                    """), "/tmp/brand-policy.json");
            assertEquals(0, silo.execInContainer("mc", "alias", "set", "local", "http://127.0.0.1:9000",
                    "brand-test-root", "brand-test-secret-12345").getExitCode());
            assertEquals(0, silo.execInContainer("mc", "admin", "policy", "create", "local", "brand-test",
                    "/tmp/brand-policy.json").getExitCode());
            assertEquals(0, silo.execInContainer("mc", "admin", "user", "add", "local", "brand-test-app",
                    "brand-test-app-secret-12345").getExitCode());
            assertEquals(0, silo.execInContainer("mc", "admin", "policy", "attach", "local", "brand-test",
                    "--user", "brand-test-app").getExitCode());
            try (S3Client restricted = S3Client.builder()
                    .endpointOverride(URI.create("http://" + silo.getHost() + ":" + silo.getMappedPort(9000)))
                    .region(Region.US_EAST_1)
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create("brand-test-app", "brand-test-app-secret-12345")))
                    .forcePathStyle(true).build()) {
                var store = new S3BrandObjectStore(restricted, "brand-assets-test");
                assertThrows(S3Exception.class, () -> restricted.listObjectsV2(
                        ListObjectsV2Request.builder().bucket("brand-assets-test").build()));
                byte[] png = { (byte) 137, 80, 78, 71 };
                store.put("tenant/asset.png", png);
                assertArrayEquals(png, store.get("tenant/asset.png"));
                store.delete("tenant/asset.png");
                assertThrows(S3Exception.class, () -> store.get("tenant/asset.png"));
            }
        }
    }
}
