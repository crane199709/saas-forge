package io.saasforge.sdk.provisioning;

import io.saasforge.sdk.mq.DeadEventOperations;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

/** MQ 不反向依赖 Provisioning，由已有上层依赖注册死信清理适配，避免 SDK 包循环。 */
@AutoConfiguration(before = TenantCleanupAutoConfiguration.class)
public final class MqTenantCleanupAutoConfiguration {
    /** 死信是基础设施记录，没有业务逻辑删除字段；租户最终删除时一并移除。 */
    @Bean
    TenantDataCleaner forgeDeadEventTenantCleaner(DeadEventOperations events) {
        return event -> events.cleanTenant(event.tenantId());
    }
}
