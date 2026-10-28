package io.saasforge.platform.tenant.config;

import io.saasforge.platform.tenant.service.TenantLifecycleService;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/** 定时处理冷静期到期与到期提醒。多实例同时扫描是安全的：每个租户只有条件更新成功的实例发布事件。 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class LifecycleSchedulingConfiguration implements SchedulingConfigurer {
    private final TenantLifecycleService lifecycle;
    private final LifecycleProperties properties;

    LifecycleSchedulingConfiguration(TenantLifecycleService lifecycle, LifecycleProperties properties) {
        this.lifecycle = lifecycle;
        this.properties = properties;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(
                () -> {
                    lifecycle.purgeDue();
                    lifecycle.remindDue();
                },
                properties.scanInterval());
    }
}
