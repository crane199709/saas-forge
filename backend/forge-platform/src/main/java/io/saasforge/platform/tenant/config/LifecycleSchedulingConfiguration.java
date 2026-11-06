package io.saasforge.platform.tenant.config;

import io.saasforge.platform.tenant.service.TenantLifecycleService;
import io.saasforge.sdk.scheduling.GlobalTask;
import org.springframework.context.annotation.Configuration;

/** Redis 互斥减少多实例重复扫描，业务条件更新仍保证每个租户只发布一次事件。 */
@Configuration(proxyBeanMethods = false)
class LifecycleSchedulingConfiguration {
    private final TenantLifecycleService lifecycle;

    LifecycleSchedulingConfiguration(TenantLifecycleService lifecycle) {
        this.lifecycle = lifecycle;
    }

    // 从已绑定配置读取间隔，让 @DefaultValue 保持唯一默认值来源。
    @GlobalTask(
            name = "tenant.lifecycle-scan",
            fixedDelay =
                    "#{@'forge.platform.lifecycle-io.saasforge.platform.tenant.config.LifecycleProperties'.scanInterval()}")
    void scanLifecycle() {
        lifecycle.purgeDue();
        lifecycle.remindDue();
    }
}
