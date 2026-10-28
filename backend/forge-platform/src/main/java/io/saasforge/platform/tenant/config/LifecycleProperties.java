package io.saasforge.platform.tenant.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Tenant Lifecycle 配置，前缀 {@code forge.platform.lifecycle}。
 *
 * @param coolingOffPeriod 删除后的 Cooling-off Period，期内可撤回，期满发布 {@code TenantDeleted}
 * @param reminderLeadTime 试用或订购到期前多久发送到期提醒
 * @param scanInterval 扫描冷静期到期与即将到期租户的间隔
 */
@ConfigurationProperties("forge.platform.lifecycle")
public record LifecycleProperties(
        @DefaultValue("30d") Duration coolingOffPeriod,
        @DefaultValue("7d") Duration reminderLeadTime,
        @DefaultValue("1m") Duration scanInterval) {}
