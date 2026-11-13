package io.saasforge.iam.operator.service;

import io.saasforge.iam.operator.mapper.OperatorMapper;
import io.saasforge.iam.operator.model.ActiveOperator;
import io.saasforge.iam.operator.model.OperatorGrants;
import io.saasforge.sdk.core.MembershipGrants;
import io.saasforge.sdk.core.OperatorStateKeys;
import io.saasforge.sdk.core.SubjectAttributes;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * 维护 Operator 在 Redis 中的 version 与平台角色、权限、Data Policy、主体属性明细，做法同 {@code MembershipStateService}（ADR-0005）。
 *
 * <p>变更先提交数据库再写 Redis；Redis 写入失败时调用方收到异常，需重试变更以重新发布。
 */
@Service
public class OperatorStateService {
    /** Operator 负责区域的扩展属性编码，平台 Data Policy 中写作 {@code subject.attributes.region}。 */
    public static final String REGION = "region";

    /** Operator 的扩展属性定义：平台侧没有可配置的扩展属性，只有负责区域。 */
    public static final Map<String, String> ATTRIBUTE_TYPES = Map.of(REGION, "string");

    // 理由同 MembershipStateService。
    private static final Duration GRANTS_TTL = Duration.ofHours(1);
    private static final DefaultRedisScript<Long> ADVANCE_VERSION = new DefaultRedisScript<>(
            "local current = redis.call('get', KEYS[1]) "
                    + "if current and tonumber(current) >= tonumber(ARGV[1]) then return 0 end "
                    + "redis.call('set', KEYS[1], ARGV[1]) return 1",
            Long.class);

    private final OperatorMapper operators;
    private final OperatorGrantSource roles;
    private final StringRedisTemplate redis;
    private final JsonMapper json;

    OperatorStateService(
            OperatorMapper operators, OperatorGrantSource roles, StringRedisTemplate redis, JsonMapper json) {
        this.operators = operators;
        this.roles = roles;
        this.redis = redis;
        this.json = json;
    }

    /** 签发 Token 时按数据库中的当前状态发布，使被淘汰或丢失的 Redis 状态随刷新恢复。 */
    public void publish(ActiveOperator operator) {
        OperatorGrants granted = roles.grantsOf(operator.operatorId());
        var grants =
                new MembershipGrants(granted.roles(), granted.permissions(), subject(operator), granted.dataPolicies());
        redis.opsForValue()
                .set(
                        OperatorStateKeys.grants(operator.operatorId(), operator.version()),
                        json.writeValueAsString(grants),
                        GRANTS_TTL);
        advance(operator.operatorId(), operator.version());
    }

    /**
     * 停用、角色、授权、Data Policy 或负责区域变更提交后调用：旧版本 Token 立即失效，刷新后按新版本签发。
     * 已停用的 Operator 不能再刷新，只推进版本，不发布明细。须在事务提交之后调用：Redis 只允许版本前进，不能领先于数据库。
     */
    public void incrementVersion(UUID operatorId) {
        Long version = operators.incrementVersion(operatorId);
        if (version == null) {
            return;
        }
        ActiveOperator active = operators.findActive(operatorId);
        if (active != null) {
            publish(active);
        } else {
            advance(operatorId, version);
        }
    }

    private void advance(UUID operatorId, long version) {
        redis.execute(ADVANCE_VERSION, List.of(OperatorStateKeys.version(operatorId)), String.valueOf(version));
    }

    // Operator 没有部门与岗位，负责区域是唯一的主体属性；类型定义随明细发布，Platform Realm 服务据此加载引用它的策略。
    private static SubjectAttributes subject(ActiveOperator operator) {
        return new SubjectAttributes(
                Set.of(),
                null,
                Set.of(),
                operator.region() == null ? Map.of() : Map.of(REGION, operator.region()),
                ATTRIBUTE_TYPES);
    }
}
