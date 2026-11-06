package io.saasforge.iam.password.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Password Policy 配置，前缀 {@code forge.iam.password}；每个 Realm 一套，由平台配置，Tenant 不能自定。
 *
 * @param tenant Tenant Realm（Identity）的规则
 * @param platform Platform Realm（Operator）的规则
 */
@ConfigurationProperties("forge.iam.password")
public record PasswordProperties(
        @DefaultValue Realm tenant, @DefaultValue Realm platform) {
    /** 密码长度上限（字节）：BCrypt 只取前 72 字节，更长的部分不参与校验，因此不允许设置。 */
    public static final int MAX_BYTES = 72;

    /**
     * 一个 Realm 的规则。
     *
     * @param minLength 最小长度（字符数），取值 8–72；NIST 800-63B 要求不低于 8
     */
    public record Realm(@DefaultValue("8") int minLength) {
        public Realm {
            if (minLength < 8 || minLength > MAX_BYTES) {
                throw new IllegalStateException("forge.iam.password.*.min-length must be between 8 and 72");
            }
        }
    }
}
