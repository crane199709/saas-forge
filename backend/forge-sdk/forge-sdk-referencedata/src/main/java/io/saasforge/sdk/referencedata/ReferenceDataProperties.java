package io.saasforge.sdk.referencedata;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 参考数据读取配置，前缀 {@code forge.reference-data}。iam 地址与令牌随环境变化，不设默认值，漏配时启动失败。
 *
 * @param iamUri iam 的内部地址，不经网关
 * @param token 本服务的服务令牌，即 iam {@code forge.iam.manifest.tokens} 中本服务的值（与上报清单的令牌相同）
 * @param cacheTtl 每个租户的缓存最长保留时间：正常情况下 iam 递增版本号即重新拉取，过期只兜底版本号缺失或 iam 递增失败
 */
@ConfigurationProperties("forge.reference-data")
public record ReferenceDataProperties(
        String iamUri, String token, @DefaultValue("10m") Duration cacheTtl) {
    /** 检查必填项。 */
    public ReferenceDataProperties {
        require(iamUri, "forge.reference-data.iam-uri");
        require(token, "forge.reference-data.token");
        if (cacheTtl.isNegative() || cacheTtl.isZero()) {
            throw new IllegalStateException("forge.reference-data.cache-ttl must be positive");
        }
    }

    private static void require(String value, String property) {
        if (value == null || value.isBlank()) throw new IllegalStateException(property + " must be set");
    }

    /** 不输出令牌，避免写入日志。 */
    @Override
    public String toString() {
        return "ReferenceDataProperties[iamUri=" + iamUri + ", cacheTtl=" + cacheTtl + "]";
    }
}
