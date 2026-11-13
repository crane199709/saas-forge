package io.saasforge.sdk.storage;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 文件治理配置，前缀 {@code forge.storage.retention}。
 *
 * @param batchSize 每次引用检查与扫描的最大候选数
 * @param referenceCheckTimeout 单批全部检查器的总超时
 */
@ConfigurationProperties("forge.storage.retention")
public record StorageRetentionProperties(
        @DefaultValue("100") int batchSize,
        @DefaultValue("5s") Duration referenceCheckTimeout) {
    public StorageRetentionProperties {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("forge.storage.retention.batch-size must be positive");
        }
        if (referenceCheckTimeout == null || referenceCheckTimeout.toMillis() <= 0) {
            throw new IllegalArgumentException("forge.storage.retention.reference-check-timeout must be at least 1ms");
        }
    }
}
