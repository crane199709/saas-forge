package io.saas.forge.tenantaccess.config;

import io.saas.forge.tenantaccess.application.authorization.PermissionAuthority;
import io.saas.forge.tenantaccess.application.brand.BrandAssetEventFactory;
import io.saas.forge.tenantaccess.application.brand.BrandAssetDisabledEventFactory;
import io.saas.forge.tenantaccess.application.brand.BrandAssetPolicyService;
import io.saas.forge.tenantaccess.application.brand.BrandAssetRepository;
import io.saas.forge.tenantaccess.application.brand.BrandAssetService;
import io.saas.forge.tenantaccess.application.brand.BrandAssetCleanupRepository;
import io.saas.forge.tenantaccess.application.brand.BrandAssetCleanupService;
import io.saas.forge.tenantaccess.application.brand.BrandAssetRemovedEventFactory;
import io.saas.forge.tenantaccess.application.brand.BrandObjectStore;
import io.saas.forge.tenantaccess.application.tenant.UuidV7Generator;
import io.saas.forge.tenantaccess.domain.outbox.OutboxEventRepository;
import io.saas.forge.tenantaccess.infrastructure.storage.S3BrandObjectStore;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.services.s3.S3Client;
import tools.jackson.databind.ObjectMapper;

@Configuration
@ConditionalOnProperty(name = "saas.forge.brand.s3.bucket")
public class BrandAssetConfiguration {
    @Bean(destroyMethod = "close")
    S3Client brandS3Client(@Value("${saas.forge.brand.s3.endpoint}") URI endpoint,
            @Value("${saas.forge.brand.s3.region}") String region,
            @Value("${saas.forge.brand.s3.access-key-file}") Path accessKey,
            @Value("${saas.forge.brand.s3.secret-key-file}") Path secretKey) throws IOException {
        String access = Files.readString(accessKey).strip();
        String secret = Files.readString(secretKey).strip();
        if (access.isEmpty() || secret.isEmpty()) throw new IllegalArgumentException("品牌 S3 凭据文件不能为空");
        return S3Client.builder().endpointOverride(endpoint).region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(access, secret)))
                .forcePathStyle(true).build();
    }

    @Bean
    BrandObjectStore brandObjectStore(S3Client brandS3Client,
            @Value("${saas.forge.brand.s3.bucket}") String bucket) {
        return new S3BrandObjectStore(brandS3Client, bucket);
    }

    @Bean
    BrandAssetEventFactory brandAssetEventFactory(ObjectMapper json, UuidV7Generator ids, Clock clock,
            @Value("${saas.forge.tenant-access.outbox-topic}") String topic) {
        return new BrandAssetEventFactory(json, ids, clock, topic);
    }

    @Bean
    BrandAssetDisabledEventFactory brandAssetDisabledEventFactory(ObjectMapper json, UuidV7Generator ids,
            @Value("${saas.forge.tenant-access.outbox-topic}") String topic) {
        return new BrandAssetDisabledEventFactory(json, ids, topic);
    }

    @Bean
    BrandAssetPolicyService brandAssetPolicyService(BrandAssetRepository assets, OutboxEventRepository outbox,
            BrandAssetDisabledEventFactory events) {
        return new BrandAssetPolicyService(assets, outbox, events);
    }

    @Bean
    BrandAssetService brandAssetService(PermissionAuthority authority, BrandAssetRepository assets,
            BrandObjectStore objects, BrandAssetEventFactory events, OutboxEventRepository outbox,
            UuidV7Generator ids, PlatformTransactionManager manager) {
        return new BrandAssetService(authority, assets, objects, events, outbox, ids,
                new TransactionTemplate(manager));
    }

    @Bean
    BrandAssetRemovedEventFactory brandAssetRemovedEventFactory(ObjectMapper json, UuidV7Generator ids, Clock clock,
            @Value("${saas.forge.tenant-access.outbox-topic}") String topic) {
        return new BrandAssetRemovedEventFactory(json, ids, clock, topic);
    }

    @Bean
    BrandAssetCleanupService brandAssetCleanupService(BrandAssetCleanupRepository assets,
            BrandObjectStore objects, OutboxEventRepository outbox, BrandAssetRemovedEventFactory events,
            PlatformTransactionManager manager, Clock clock) {
        return new BrandAssetCleanupService(assets, objects, outbox, events, new TransactionTemplate(manager), clock);
    }

    @Bean
    BrandAssetCleanupWorker brandAssetCleanupWorker(BrandAssetCleanupService cleanup) {
        return new BrandAssetCleanupWorker(cleanup);
    }

    public static final class BrandAssetCleanupWorker {
        private static final Logger log = LoggerFactory.getLogger(BrandAssetCleanupWorker.class);
        private final BrandAssetCleanupService cleanup;
        BrandAssetCleanupWorker(BrandAssetCleanupService cleanup) { this.cleanup = cleanup; }

        @Scheduled(fixedDelay = 60_000)
        public void clean() {
            for (int i = 0; i < 100; i++) {
                try {
                    if (!cleanup.cleanNext()) return;
                } catch (RuntimeException retryLater) {
                    log.warn("品牌素材清理失败，将按持久状态重试", retryLater);
                    return;
                }
            }
        }
    }
}
