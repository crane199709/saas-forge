package io.saas.forge.gateway.config;

import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;

/** 读取 IAM 唯一写入的撤销索引；未就绪、读取失败和命中都不能继续转发请求。 */
final class RedisGatewayUserTokenRevocationChecker implements GatewayUserTokenRevocationChecker {

    private final GatewayRedisValueReader redis;
    private final GatewayIamRevocationRedisKeys keys;

    RedisGatewayUserTokenRevocationChecker(StringRedisTemplate redis, String environment) {
        this(redis.opsForValue()::multiGet, environment);
    }

    RedisGatewayUserTokenRevocationChecker(GatewayRedisValueReader redis, String environment) {
        this.redis = redis;
        this.keys = new GatewayIamRevocationRedisKeys(environment);
    }

    @Override
    public void assertAllowed(UUID jti, String kid, UUID membershipId, UUID tenantId) {
        assertAllowed(jti, kid, membershipId, tenantId, null);
    }

    @Override
    public void assertAllowed(UUID jti, String kid, UUID membershipId, UUID tenantId, UUID identityId) {
        List<String> revocationKeys = keys(jti, kid, membershipId, tenantId, identityId);
        try {
            List<String> values = redis.multiGet(revocationKeys);
            if (values == null || values.size() != revocationKeys.size() || !"1".equals(values.get(0))) {
                throw new GatewayTokenRevocationStatusUnavailableException();
            }
            for (int index = 1; index < values.size(); index++) {
                if (values.get(index) != null) {
                    throw new GatewayUserTokenInvalidException();
                }
            }
        } catch (DataAccessException exception) {
            throw new GatewayTokenRevocationStatusUnavailableException(exception);
        }
    }

    private List<String> keys(UUID jti, String kid, UUID membershipId, UUID tenantId, UUID identityId) {
        var keysToRead = new java.util.ArrayList<String>();
        keysToRead.add(keys.ready());
        keysToRead.add(keys.jti(jti));
        keysToRead.add(keys.kid(kid));
        if (identityId != null) keysToRead.add(keys.identityFence(identityId));
        if (membershipId == null) {
            return keysToRead;
        }
        keysToRead.add(keys.tenantFence(tenantId));
        keysToRead.add(keys.membershipFence(membershipId));
        return keysToRead;
    }
}

@FunctionalInterface
interface GatewayRedisValueReader {
    List<String> multiGet(List<String> keys);
}
