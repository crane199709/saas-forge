package io.saasforge.sdk.scheduling;

import io.saasforge.sdk.core.BusinessZoneProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** 引入 starter 后自动注册 Spring Bean 上声明的任务，无须 EnableScheduling。 */
@AutoConfiguration(afterName = "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration")
@EnableConfigurationProperties({BusinessZoneProperties.class, SchedulingProperties.class})
public final class SchedulingAutoConfiguration {
    /** 触发器独立于任务执行体；服务可通过同名 TaskScheduler Bean 提供自己的时钟与调度器。 */
    @Bean
    @ConditionalOnMissingBean(name = "forgeTaskScheduler")
    public ThreadPoolTaskScheduler forgeTaskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("forge-task-trigger-");
        return scheduler;
    }

    /**
     * 提供本服务的 Task Run 查询，不向调用方暴露锁令牌。
     *
     * @param redis 调度使用的全局 Redis 模板
     * @param environment 读取服务隔离名称
     * @return 最近运行查询
     */
    @Bean
    public TaskRunHistory forgeTaskRunHistory(StringRedisTemplate redis, Environment environment) {
        return new TaskRunHistory(redis, environment.getRequiredProperty("spring.application.name"));
    }

    /** 延迟到所有单例初始化后启动任务，避免执行体访问尚未初始化的服务。 */
    @Bean
    static TaskRegistrar forgeTaskRegistrar(
            ConfigurableListableBeanFactory beans,
            Environment environment,
            ObjectProvider<SchedulingProperties> properties) {
        return new TaskRegistrar(beans, environment, properties);
    }
}
