package io.saasforge.sdk.mq;

import java.util.List;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * 业务服务引入 starter 即可发布与订阅事件。
 *
 * <p>服务须在自己的 Flyway 迁移中创建 {@code forge_outbox} 与 {@code forge_consumed_message} 两张表，建表语句见
 * {@code io/saasforge/sdk/mq/schema.sql}。两张表都不带租户隔离：投递器与去重都需要跨租户访问。
 */
@AutoConfiguration(afterName = "com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration")
@EnableConfigurationProperties(MqProperties.class)
public final class MqAutoConfiguration {
    // 消息体是跨服务契约，不跟随服务对 Spring 管理的 JsonMapper 的定制
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /**
     * 事件发布入口。
     *
     * @param sqlSession 参与 Spring 事务的 SqlSession，保证 Outbox 写入与业务数据同一事务
     * @param dispatcher 投递器，{@code forge.mq.enabled=false} 时不存在，事件留在 Outbox 中
     */
    @Bean
    EventPublisher eventPublisher(SqlSessionTemplate sqlSession, ObjectProvider<OutboxDispatcher> dispatcher) {
        return new EventPublisher(
                mapper(sqlSession, OutboxMapper.class), JSON, () -> dispatcher.ifAvailable(OutboxDispatcher::wakeUp));
    }

    /** 投递器与消费端，可在测试等不连接 MQ 的环境中关闭。 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "forge.mq", name = "enabled", matchIfMissing = true)
    static class MessagingConfiguration {
        @Bean
        ClientConfiguration forgeMqClientConfiguration(MqProperties properties) {
            return ClientConfiguration.newBuilder()
                    .setEndpoints(properties.endpoints())
                    .enableSsl(properties.ssl())
                    .setRequestTimeout(properties.requestTimeout())
                    .build();
        }

        @Bean
        OutboxDispatcher outboxDispatcher(
                SqlSessionTemplate sqlSession,
                PlatformTransactionManager transactionManager,
                ClientConfiguration configuration,
                MqProperties properties) {
            return new OutboxDispatcher(
                    mapper(sqlSession, OutboxMapper.class),
                    new TransactionTemplate(transactionManager),
                    new RocketMqSender(configuration),
                    properties);
        }

        @Bean
        @ConditionalOnBean(EventHandler.class)
        EventConsumer eventConsumer(
                SqlSessionTemplate sqlSession,
                PlatformTransactionManager transactionManager,
                ClientConfiguration configuration,
                MqProperties properties,
                List<EventHandler<?>> handlers,
                Environment environment) {
            String consumerGroup = properties.consumerGroup() != null
                    ? properties.consumerGroup()
                    : environment.getRequiredProperty("spring.application.name");
            return new EventConsumer(
                    configuration,
                    consumerGroup,
                    handlers,
                    mapper(sqlSession, ConsumedMessageMapper.class),
                    new TransactionTemplate(transactionManager),
                    JSON);
        }
    }

    /**
     * 在 SqlSessionFactory 建好之后注册 SDK 的 Mapper；MyBatis 按接口所在包加载同名 XML。
     *
     * <p>不能在 {@code ConfigurationCustomizer} 中注册：那时 MyBatis-Plus 还没把 GlobalConfig 绑定到 Configuration，
     * {@code addMapper} 会先绑定一份默认 GlobalConfig，之后服务配置的 MetaObjectHandler、逻辑删除等全局配置都不再生效。
     * 也不用 Mapper 扫描注册：声明 MapperFactoryBean 会让 MyBatis-Plus 跳过服务自身 {@code @Mapper} 的自动扫描。
     */
    static <T> T mapper(SqlSessionTemplate sqlSession, Class<T> type) {
        var configuration = sqlSession.getConfiguration();
        if (!configuration.hasMapper(type)) {
            configuration.addMapper(type);
        }
        return sqlSession.getMapper(type);
    }
}
