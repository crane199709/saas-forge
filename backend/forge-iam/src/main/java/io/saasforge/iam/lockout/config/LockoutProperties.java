package io.saasforge.iam.lockout.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Login Lockout 配置，前缀 {@code forge.iam.lockout}；两个 Realm 分别配置（账号安全规格第 3 节）。
 *
 * @param tenant Tenant Realm（Identity）的规则
 * @param platform Platform Realm（Operator）的规则
 */
@ConfigurationProperties("forge.iam.lockout")
public record LockoutProperties(
        @DefaultValue Realm tenant, @DefaultValue Realm platform) {

    /**
     * 一个 Realm 的规则。默认值取规格的参考值：15 分钟内 5 次失败锁定 15 分钟。
     *
     * @param maxFailures 窗口内同一登录标识、Identity 或 Operator 的失败次数达到此值即锁定
     * @param window 计数窗口，从窗口内第一次失败起算
     * @param lockDuration 锁定时长，到期自动解除
     * @param ipMaxFailures 窗口内同一客户端 IP 的失败次数（不论标识）达到此值即锁定该 IP 同样时长；须远大于
     *     {@code maxFailures}，避免误伤共用出口 IP 的正常用户
     */
    public record Realm(
            @DefaultValue("5") int maxFailures,
            @DefaultValue("15m") Duration window,
            @DefaultValue("15m") Duration lockDuration,
            @DefaultValue("100") int ipMaxFailures) {
        public Realm {
            if (maxFailures < 1 || ipMaxFailures < 1) {
                throw new IllegalStateException(
                        "forge.iam.lockout.*.max-failures and ip-max-failures must be positive");
            }
            if (window.toMillis() < 1 || lockDuration.toMillis() < 1) {
                throw new IllegalStateException("forge.iam.lockout.*.window and lock-duration must be positive");
            }
        }
    }
}
