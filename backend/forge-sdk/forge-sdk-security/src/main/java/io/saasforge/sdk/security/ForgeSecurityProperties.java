package io.saasforge.sdk.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * JWT 验签配置，前缀 {@code forge.security}（ADR-0003）。
 *
 * <p>issuer 与 JWKS 地址不设默认值：漏配时验签器创建失败、服务拒绝启动，而不是静默信任某个本地地址。
 * 服务只校验所选 Realm 的两项，网关两个 Realm 都要配置。
 *
 * @param realm 服务接受哪个 Realm 的 Token，取值 {@code tenant} 或 {@code platform}
 * @param issuer Tenant Realm 的 issuer，须与 IAM 签发的 {@code iss} 完全一致
 * @param jwkSetUri Tenant Realm 的 JWKS 地址，指向 IAM 内部地址，公钥缓存后本地验签
 * @param platform Platform Realm 的 issuer 与 JWKS 地址
 */
@ConfigurationProperties("forge.security")
public record ForgeSecurityProperties(
        @DefaultValue("tenant") String realm,
        String issuer,
        String jwkSetUri,
        @DefaultValue Platform platform) {

    /**
     * Platform Realm 的验签配置。
     *
     * @param issuer Platform Realm 的 issuer，不能与 Tenant Realm 相同
     * @param jwkSetUri Platform Realm 的 JWKS 地址
     */
    public record Platform(String issuer, String jwkSetUri) {}
}
