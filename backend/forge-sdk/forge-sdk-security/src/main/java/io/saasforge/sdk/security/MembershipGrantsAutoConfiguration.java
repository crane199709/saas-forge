package io.saasforge.sdk.security;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Servlet 业务服务使用 Redis 时提供 {@link MembershipGrantStore}；网关只比对版本，不读取明细。 */
@AutoConfiguration(afterName = "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(StringRedisTemplate.class)
public final class MembershipGrantsAutoConfiguration {
    /** 同一进程共用一份本地缓存。 */
    @Bean
    @ConditionalOnBean(StringRedisTemplate.class)
    @ConditionalOnMissingBean
    public MembershipGrantStore membershipGrantStore(StringRedisTemplate redis) {
        return new MembershipGrantStore(redis);
    }
}
