package io.saasforge.sdk.numbering;

import io.saasforge.sdk.core.BusinessZoneProperties;
import io.saasforge.sdk.core.CurrentActorProvider;
import io.saasforge.sdk.provisioning.TenantCleanupAutoConfiguration;
import io.saasforge.sdk.provisioning.TenantDataCleaner;
import java.time.Clock;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 业务服务引入 starter 即可注入 {@link BusinessNumbers}；引入即视为使用编号，未配置 {@code forge.business-zone} 时启动失败。
 *
 * <p>服务须在自己的 Flyway 迁移中创建 {@code forge_business_number}，建表语句见 {@code io/saasforge/sdk/numbering/schema.sql}。
 *
 * <p>排在 {@link TenantCleanupAutoConfiguration} 之前：它只在存在 {@link TenantDataCleaner} Bean 时订阅租户删除。
 */
@AutoConfiguration(
        afterName = "com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration",
        before = TenantCleanupAutoConfiguration.class)
@EnableConfigurationProperties(BusinessZoneProperties.class)
public final class NumberingAutoConfiguration {
    /**
     * 取号入口。
     *
     * @param sqlSession 参与 Spring 事务的 SqlSession，保证计数与业务数据同一事务
     * @param clock 服务声明 {@link Clock} Bean 时以它取当前时刻（便于测试跨计数周期），否则取系统时钟
     */
    @Bean
    public BusinessNumbers businessNumbers(
            SqlSessionTemplate sqlSession,
            BusinessZoneProperties businessZone,
            ObjectProvider<Clock> clock,
            ObjectProvider<CurrentActorProvider> actors) {
        // 先于其他初始化检查，漏配时的报错直接指向 forge.business-zone
        var zone = businessZone.require("Business Number");
        return new BusinessNumbers(mapper(sqlSession), zone, clock.getIfAvailable(Clock::systemUTC), () -> {
            CurrentActorProvider provider = actors.getIfAvailable();
            return provider == null ? null : provider.currentActor().orElse(null);
        });
    }

    /** 租户删除时逻辑删除该租户的计数行。 */
    @Bean
    TenantDataCleaner businessNumberCleaner(SqlSessionTemplate sqlSession) {
        BusinessNumberMapper mapper = mapper(sqlSession);
        return event -> mapper.markTenantDeleted();
    }

    /** 在 SqlSessionFactory 建好之后注册 Mapper，理由见 {@code MqAutoConfiguration#mapper}。 */
    private static BusinessNumberMapper mapper(SqlSessionTemplate sqlSession) {
        var configuration = sqlSession.getConfiguration();
        if (!configuration.hasMapper(BusinessNumberMapper.class)) {
            configuration.addMapper(BusinessNumberMapper.class);
        }
        return sqlSession.getMapper(BusinessNumberMapper.class);
    }
}
