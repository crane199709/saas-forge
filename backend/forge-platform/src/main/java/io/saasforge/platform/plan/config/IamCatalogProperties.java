package io.saasforge.platform.plan.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 读取 iam 权限码目录的配置，前缀 {@code forge.platform.iam}。地址与令牌随环境变化，不设默认值，漏配时启动失败。
 *
 * @param uri iam 的内部地址，不经网关（网关拒绝 {@code /internal/**}）
 * @param token 本服务的服务令牌，须与 iam {@code forge.iam.manifest.tokens} 中 {@code forge-platform} 的值一致
 */
@ConfigurationProperties("forge.platform.iam")
public record IamCatalogProperties(String uri, String token) {
    public IamCatalogProperties {
        require(uri, "forge.platform.iam.uri");
        require(token, "forge.platform.iam.token");
    }

    private static void require(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(property + " must be set");
        }
    }

    @Override
    public String toString() {
        return "IamCatalogProperties[uri=" + uri + "]";
    }
}
