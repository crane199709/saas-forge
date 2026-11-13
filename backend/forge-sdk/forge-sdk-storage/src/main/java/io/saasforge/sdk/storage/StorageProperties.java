package io.saasforge.sdk.storage;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 对象存储配置，前缀 {@code forge.storage}。地址、bucket 与密钥随环境变化，不设默认值，漏配时启动失败。
 *
 * @param endpoint S3 兼容存储的地址，同时是预签名 URL 的地址，须能被浏览器访问
 * @param region 签名使用的区域；多数 S3 兼容实现不校验，保持默认即可
 * @param bucket 所有租户共用的 bucket，须预先创建
 * @param accessKey 访问密钥 ID
 * @param secretKey 访问密钥
 * @param pathStyle 是否以路径形式（{@code endpoint/bucket/key}）访问；多数自建 S3 兼容实现只支持路径形式
 * @param uploadUrlTtl 上传预签名 URL 的有效期；未确认对象此后可失效，容量在最终物理删除后才释放
 */
@ConfigurationProperties("forge.storage")
public record StorageProperties(
        URI endpoint,
        @DefaultValue("us-east-1") String region,
        String bucket,
        String accessKey,
        String secretKey,
        @DefaultValue("true") boolean pathStyle,
        @DefaultValue("15m") Duration uploadUrlTtl) {
    public StorageProperties {
        Objects.requireNonNull(endpoint, "forge.storage.endpoint must be configured");
        requireText(bucket, "forge.storage.bucket");
        requireText(accessKey, "forge.storage.access-key");
        requireText(secretKey, "forge.storage.secret-key");
    }

    private static void requireText(String value, String key) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(key + " must be configured");
        }
    }

    /** 不输出密钥，避免写入日志。 */
    @Override
    public String toString() {
        return "StorageProperties[endpoint=" + endpoint + ", region=" + region + ", bucket=" + bucket + ", accessKey="
                + accessKey + ", pathStyle=" + pathStyle + ", uploadUrlTtl=" + uploadUrlTtl + "]";
    }
}
