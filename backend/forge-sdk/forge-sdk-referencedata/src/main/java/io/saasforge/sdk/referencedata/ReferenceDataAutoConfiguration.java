package io.saasforge.sdk.referencedata;

import io.saasforge.sdk.mq.MqAutoConfiguration;
import java.time.Clock;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * 业务服务引入 starter 并配置 {@code forge.reference-data} 即可注入 {@link ReferenceData}、使用 {@link DictionaryValue}。
 *
 * <p>排在 {@link MqAutoConfiguration} 之后：开启消息（{@code forge.mq.enabled}）时才有 RocketMQ 客户端配置，据此订阅变更事件；
 * 关闭时只靠缓存过期刷新。
 */
@AutoConfiguration(after = MqAutoConfiguration.class)
@EnableConfigurationProperties(ReferenceDataProperties.class)
public final class ReferenceDataAutoConfiguration {
    /**
     * 参考数据读取入口。
     *
     * @param clock 服务声明 {@link Clock} Bean 时以它判断缓存过期（便于测试），否则取系统时钟
     */
    @Bean
    public ReferenceData referenceData(
            ReferenceDataProperties properties, Environment environment, ObjectProvider<Clock> clock) {
        var client = new ReferenceDataClient(properties);
        return new ReferenceData(
                client::load,
                environment.getRequiredProperty("spring.application.name"),
                properties.cacheTtl(),
                clock.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    @ConditionalOnBean(ClientConfiguration.class)
    ReferenceDataChangedListener referenceDataChangedListener(
            ClientConfiguration configuration, ReferenceData referenceData, Environment environment) {
        return new ReferenceDataChangedListener(
                configuration, environment.getRequiredProperty("spring.application.name"), referenceData);
    }
}
