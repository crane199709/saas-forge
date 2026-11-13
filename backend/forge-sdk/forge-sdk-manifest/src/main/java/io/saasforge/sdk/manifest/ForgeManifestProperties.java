package io.saasforge.sdk.manifest;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Resource Manifest 上报配置，前缀 {@code forge.manifest}。
 *
 * <p>开启上报时版本、iam 地址与令牌都必须配置：地址与令牌随环境变化，不设默认值，漏配时启动失败。
 *
 * @param enabled 是否在启动时上报；不连接 iam 的测试可关闭
 * @param version 清单版本，改动权限码、名称或菜单时必须升级
 * @param iamUri iam 的内部地址，不经网关
 * @param token 本服务的上报令牌，由管理员登记并一次性交付，不能替代 Ops Token
 */
@ConfigurationProperties("forge.manifest")
public record ForgeManifestProperties(
        @DefaultValue("true") boolean enabled, String version, String iamUri, String token) {
    /** 开启上报时检查必填项。 */
    public ForgeManifestProperties {
        if (enabled) {
            require(version, "forge.manifest.version");
            require(iamUri, "forge.manifest.iam-uri");
            require(token, "forge.manifest.token");
        }
    }

    private static void require(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(property + " must be set");
        }
    }

    /** 不输出 {@code token}：record 默认的 toString 会让上报令牌随配置对象进入日志。 */
    @Override
    public String toString() {
        return "ForgeManifestProperties[enabled=" + enabled + ", version=" + version + ", iamUri=" + iamUri + "]";
    }
}
