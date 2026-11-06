package io.saasforge.iam.security.service;

import java.time.Duration;
import java.util.Set;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.stereotype.Service;

/**
 * 记录每个 Identity 或 Operator 已认证的 iam 登录会话，供改密码或重设密码后终止：登录会话在有效期内可以再走授权码流程签发新 Token，
 * 只吊销 Refresh Token 不够。
 *
 * <p>Spring Session 的默认 Redis 仓库不按主体建索引，因此在登录成功时自行登记。
 */
@Service
public class LoginSessionRegistry {
    private static final String PREFIX = "iam:global:principal-sessions:";
    // 会话随访问续期，没有绝对上限，索引因此不能按会话超时过期；每次登录时清掉已失效的会话标识并续期，长期不登录的主体由 TTL 回收。
    private static final Duration INDEX_TTL = Duration.ofDays(30);

    private final StringRedisTemplate redis;
    private final SessionRepository<? extends Session> sessions;

    LoginSessionRegistry(StringRedisTemplate redis, SessionRepository<? extends Session> sessions) {
        this.redis = redis;
        this.sessions = sessions;
    }

    /** 登录成功、会话中已存入认证主体后调用。 */
    public void register(String principalName, String sessionId) {
        String key = PREFIX + principalName;
        Set<String> ids = redis.opsForSet().members(key);
        if (ids != null) {
            for (String id : ids) {
                if (sessions.findById(id) == null) redis.opsForSet().remove(key, id);
            }
        }
        redis.opsForSet().add(key, sessionId);
        redis.expire(key, INDEX_TTL);
    }

    /**
     * 终止主体的全部登录会话。
     *
     * @param principalName Identity 或 Operator 标识
     */
    public void invalidateAll(String principalName) {
        String key = PREFIX + principalName;
        Set<String> ids = redis.opsForSet().members(key);
        if (ids == null) return;
        ids.forEach(sessions::deleteById);
        redis.delete(key);
    }
}
