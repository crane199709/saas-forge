package io.saasforge.sdk.tenant;

import io.saasforge.sdk.core.CurrentTenant;
import java.nio.charset.StandardCharsets;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;

/**
 * 键自动加 {@code {service}:{tenant_id}:} 前缀的字符串 Redis 模板，读出的键去掉前缀。
 *
 * <p>前缀在键序列化时按当前租户计算，因此无租户上下文时访问会显式失败。全局数据（不属于任何租户）
 * 请使用 Spring Boot 提供的 {@code StringRedisTemplate}。经 {@code execute(RedisCallback)} 直接操作连接时
 * 不经过键序列化，得不到前缀。
 */
public final class TenantRedisTemplate extends RedisTemplate<String, String> {
    /**
     * 创建模板。
     *
     * @param connectionFactory Redis 连接工厂
     * @param service 服务名，作为键的第一段
     */
    public TenantRedisTemplate(RedisConnectionFactory connectionFactory, String service) {
        setConnectionFactory(connectionFactory);
        setKeySerializer(new TenantKeySerializer(service));
        setValueSerializer(RedisSerializer.string());
        setHashKeySerializer(RedisSerializer.string());
        setHashValueSerializer(RedisSerializer.string());
        afterPropertiesSet();
    }

    private record TenantKeySerializer(String service) implements RedisSerializer<String> {
        @Override
        public byte[] serialize(String key) {
            return key == null ? null : (prefix() + key).getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public String deserialize(byte[] bytes) {
            if (bytes == null) {
                return null;
            }
            String key = new String(bytes, StandardCharsets.UTF_8);
            String prefix = prefix();
            return key.startsWith(prefix) ? key.substring(prefix.length()) : key;
        }

        private String prefix() {
            return service + ":" + CurrentTenant.require() + ":";
        }
    }
}
