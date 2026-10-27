package io.saasforge.iam.security.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * IAM 授权服务器配置，前缀 {@code forge.iam}。
 *
 * @param redirectUri tenant-app 的回调地址，必须与 Shell 同源
 * @param signingKeyFile Tenant Realm 签名密钥文件，不存在时生成
 * @param clientSecret 同进程 Token 交换适配器使用的客户端密钥，为空时每次启动随机生成；多实例需一致
 * @param tokenBaseUri 适配器调用标准 Token 端点的地址，为空时使用本机地址
 * @param platform Platform Realm 的配置
 */
@ConfigurationProperties("forge.iam")
public record IamProperties(
        @DefaultValue("http://localhost:5174/") String redirectUri,
        String signingKeyFile,
        String clientSecret,
        String tokenBaseUri,
        @DefaultValue Platform platform) {
    /**
     * 密钥文件不存在时会生成新密钥：路径漏配时各实例或重建后的容器各用一把密钥，已签发的 Token 全部验签失败，所以必须显式配置。
     */
    public IamProperties {
        requireKeyFile(signingKeyFile, "forge.iam.signing-key-file");
        requireKeyFile(platform.signingKeyFile(), "forge.iam.platform.signing-key-file");
        clientSecret = clientSecret == null ? "" : clientSecret;
        tokenBaseUri = tokenBaseUri == null ? "" : tokenBaseUri;
    }

    private static void requireKeyFile(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(property + " must be set");
        }
    }

    /**
     * Platform Realm 的配置。
     *
     * @param redirectUri platform-console 的回调地址
     * @param signingKeyFile Platform Realm 签名密钥文件，与 Tenant Realm 分开
     * @param mfaRequired 是否强制 Operator 使用 MFA；MFA 尚未实现，开启后拒绝所有 Operator 登录与换发
     */
    public record Platform(
            @DefaultValue("http://localhost:5173/") String redirectUri,
            String signingKeyFile,
            @DefaultValue("false") boolean mfaRequired) {}
}
