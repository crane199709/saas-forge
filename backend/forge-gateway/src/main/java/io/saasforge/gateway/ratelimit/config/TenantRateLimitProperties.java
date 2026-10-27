package io.saasforge.gateway.ratelimit.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 网关按租户限流的阈值。第一期所有租户使用同一默认阈值；按套餐设置阈值经 {@link
 * io.saasforge.gateway.ratelimit.service.TenantQpsOverrides} 扩展。
 *
 * <p>按网关单实例计数：多实例部署时单个租户实际可得 {@code defaultQps × 实例数}。阈值在启动时加载，修改后需重启网关。
 *
 * @param defaultQps 每个租户在单个网关实例上每秒允许的请求数
 */
@ConfigurationProperties("forge.gateway.rate-limit")
public record TenantRateLimitProperties(@DefaultValue("100") int defaultQps) {}
