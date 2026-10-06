package io.saas.forge.remotedelivery;

import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.cloud.client.discovery.event.InstanceRegisteredEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/** 只有服务注册表确认注册成功，实例才进入 Ready。 */
@Component("nacosRegistrationReadiness")
final class NacosRegistrationReadinessHealthIndicator
        implements HealthIndicator, ApplicationListener<InstanceRegisteredEvent<?>> {
    private final AtomicBoolean registered = new AtomicBoolean();

    @Override
    public void onApplicationEvent(InstanceRegisteredEvent<?> event) {
        registered.set(true);
    }

    @Override
    public Health health() {
        return registered.get()
                ? Health.up().withDetail("registration", "completed").build()
                : Health.down().withDetail("registration", "pending").build();
    }
}
