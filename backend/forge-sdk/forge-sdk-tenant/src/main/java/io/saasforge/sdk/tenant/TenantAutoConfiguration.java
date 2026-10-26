package io.saasforge.sdk.tenant;

import com.baomidou.mybatisplus.autoconfigure.ConfigurationCustomizer;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import io.saasforge.sdk.core.CurrentTenant;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.task.TaskDecorator;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/** 业务服务引入 starter 即获得租户隔离，业务代码不需要写任何租户条件。 */
@AutoConfiguration(
        afterName = "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration",
        beforeName = "org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration")
@EnableConfigurationProperties(TenantProperties.class)
public final class TenantAutoConfiguration {
    /**
     * 为查询、插入、更新、删除注入 {@code tenant_id} 条件，并为 UUID 主键生成 UUIDv7。
     *
     * <p>MyBatis-Plus 自动配置会收集所有 {@code Interceptor} Bean，业务服务另行声明的拦截器不会替换本拦截器。
     */
    @Bean
    public MybatisPlusInterceptor tenantMybatisPlusInterceptor(TenantProperties properties) {
        var interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new UuidV7KeyInnerInterceptor());
        interceptor.addInnerInterceptor(
                new TenantLineInnerInterceptor(new CurrentTenantLineHandler(properties.ignoreTables())));
        return interceptor;
    }

    /** 让 UUID 主键与 {@code uuid} 列可直接映射到实体。 */
    @Bean
    public ConfigurationCustomizer uuidTypeHandlerCustomizer() {
        return configuration -> configuration.getTypeHandlerRegistry().register(UUID.class, new UuidTypeHandler());
    }

    /**
     * 包装应用的数据源，使每个连接都带上 RLS 会话变量。
     *
     * <p>未声明 {@link TenantDataSourceRouter} 时使用 {@link SingleDataSourceRouter}。
     */
    @Bean
    public static BeanPostProcessor tenantSessionDataSourcePostProcessor(
            ObjectProvider<TenantDataSourceRouter> routers) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (!(bean instanceof DataSource dataSource) || bean instanceof TenantSessionDataSource) {
                    return bean;
                }
                return new TenantSessionDataSource(
                        dataSource, routers.getIfAvailable(() -> new SingleDataSourceRouter(dataSource)));
            }
        };
    }

    /** {@code @Async} 等经 Spring 任务执行器提交的任务沿用提交线程的租户。 */
    @Bean
    @ConditionalOnMissingBean(TaskDecorator.class)
    public TaskDecorator tenantTaskDecorator() {
        return CurrentTenant::wrap;
    }

    /** 服务使用 Redis 时提供带租户前缀的模板。 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(RedisConnectionFactory.class)
    @ConditionalOnBean(RedisConnectionFactory.class)
    static class RedisConfiguration {
        /** 键前缀的服务段取自 {@code spring.application.name}，未配置时启动失败，避免不同服务的键混在一起。 */
        @Bean
        @ConditionalOnMissingBean
        TenantRedisTemplate tenantRedisTemplate(RedisConnectionFactory connectionFactory, Environment environment) {
            return new TenantRedisTemplate(
                    connectionFactory, environment.getRequiredProperty("spring.application.name"));
        }
    }
}
