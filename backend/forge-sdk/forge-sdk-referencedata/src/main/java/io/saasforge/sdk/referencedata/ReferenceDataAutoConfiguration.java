package io.saasforge.sdk.referencedata;

import io.saasforge.sdk.core.ReferenceDataKeys;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 业务服务引入 starter 并配置 {@code forge.reference-data} 即可注入 {@link ReferenceData}、使用 {@link DictionaryValue}。
 *
 * <p>排在 Spring Boot 的 Redis 自动配置之后：服务使用 Redis 时按 iam 发布的版本号失效缓存；没有 Redis 时只靠缓存过期刷新。
 */
@AutoConfiguration(afterName = "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration")
@EnableConfigurationProperties(ReferenceDataProperties.class)
public final class ReferenceDataAutoConfiguration {
    /**
     * 参考数据读取入口。
     *
     * @param redis 与 iam 共用的 Redis；版本号是 iam 的全局键，不使用租户前缀模板
     * @param clock 服务声明 {@link Clock} Bean 时以它判断缓存过期（便于测试），否则取系统时钟
     */
    @Bean
    public ReferenceData referenceData(
            ReferenceDataProperties properties,
            Environment environment,
            ObjectProvider<StringRedisTemplate> redis,
            ObjectProvider<Clock> clock) {
        var client = new ReferenceDataClient(properties);
        StringRedisTemplate template = redis.getIfAvailable();
        return new ReferenceData(
                client::load,
                template == null
                        ? tenantId -> null
                        : tenantId -> template.opsForValue().get(ReferenceDataKeys.version(tenantId)),
                environment.getRequiredProperty("spring.application.name"),
                properties.cacheTtl(),
                clock.getIfAvailable(Clock::systemUTC));
    }
}
