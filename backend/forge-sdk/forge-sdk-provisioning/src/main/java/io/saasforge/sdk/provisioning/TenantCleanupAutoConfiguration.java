package io.saasforge.sdk.provisioning;

import io.saasforge.sdk.core.CurrentTenant;
import io.saasforge.sdk.mq.EventHandler;
import io.saasforge.sdk.mq.MqAutoConfiguration;
import io.saasforge.sdk.tenant.TenantWriteGuard;
import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;

/**
 * 服务声明 {@link TenantDataCleaner} Bean 后，SDK 订阅 {@link TenantDeleted} 并调用清理器。
 *
 * <p>排在 {@link MqAutoConfiguration} 之前：消费端只在存在 {@link EventHandler} Bean 时创建。
 */
@AutoConfiguration(before = MqAutoConfiguration.class)
@ConditionalOnBean(TenantDataCleaner.class)
public final class TenantCleanupAutoConfiguration {
    /**
     * 与初始化不同，清理失败不回报而是抛出：事务回滚、消息重投，直到清理完成。
     *
     * @param cleaners 本服务的全部清理器
     * @return {@link TenantDeleted} 的订阅
     */
    @Bean
    EventHandler<TenantDeleted> tenantDeletedHandler(List<TenantDataCleaner> cleaners) {
        return new EventHandler<>() {
            @Override
            public String topic() {
                return TenantCreated.TOPIC;
            }

            @Override
            public Class<TenantDeleted> eventType() {
                return TenantDeleted.class;
            }

            @Override
            public void handle(TenantDeleted event) {
                // 消费端已按消息头恢复租户并在其上开启事务（RLS 会话变量随连接设置），这里只能确认二者一致，不能换租户。
                if (!event.tenantId().equals(CurrentTenant.find().orElse(null))) {
                    throw new IllegalStateException("TenantDeleted for " + event.tenantId()
                            + " must be published in the context of that tenant");
                }
                CurrentTenant.run(
                        event.tenantId(),
                        () -> TenantWriteGuard.exempt(() -> cleaners.forEach(cleaner -> cleaner.clean(event))));
            }
        };
    }
}
