package io.saasforge.sdk.provisioning;

import io.saasforge.sdk.mq.EventHandler;
import io.saasforge.sdk.mq.EventPublisher;
import io.saasforge.sdk.mq.MqAutoConfiguration;
import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 服务声明 {@link TenantInitializer} Bean 后，SDK 订阅 {@link TenantCreated} 与 {@link TenantInitializationRequested}，
 * 调用初始化器并发布 {@link TenantInitializationReported}。
 *
 * <p>排在 {@link MqAutoConfiguration} 之前：消费端只在存在 {@link EventHandler} Bean 时创建。
 */
@AutoConfiguration(before = MqAutoConfiguration.class)
@ConditionalOnBean(TenantInitializer.class)
public final class ProvisioningAutoConfiguration {
    /** 回报中的服务名取 {@code spring.application.name}，须与 platform 配置的待初始化服务名一致。 */
    @Bean
    TenantInitializationRunner tenantInitializationRunner(
            List<TenantInitializer> initializers,
            PlatformTransactionManager transactionManager,
            EventPublisher publisher,
            Environment environment) {
        var savepoint = new TransactionTemplate(transactionManager);
        savepoint.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        return new TenantInitializationRunner(
                initializers, savepoint, publisher, environment.getRequiredProperty("spring.application.name"));
    }

    @Bean
    EventHandler<TenantCreated> tenantCreatedHandler(TenantInitializationRunner runner) {
        return new EventHandler<>() {
            @Override
            public String topic() {
                return TenantCreated.TOPIC;
            }

            @Override
            public Class<TenantCreated> eventType() {
                return TenantCreated.class;
            }

            @Override
            public void handle(TenantCreated event) {
                runner.run(event);
            }
        };
    }

    @Bean
    EventHandler<TenantInitializationRequested> tenantInitializationRequestedHandler(
            TenantInitializationRunner runner) {
        return new EventHandler<>() {
            @Override
            public String topic() {
                return TenantCreated.TOPIC;
            }

            @Override
            public Class<TenantInitializationRequested> eventType() {
                return TenantInitializationRequested.class;
            }

            @Override
            public void handle(TenantInitializationRequested event) {
                if (runner.service().equals(event.service())) {
                    runner.run(event.tenant());
                }
            }
        };
    }
}
