package io.saasforge.sdk.abac.data;

import io.saasforge.sdk.abac.DataResourceDefinition;
import io.saasforge.sdk.abac.Expression;
import io.saasforge.sdk.abac.PolicyEvaluator;
import io.saasforge.sdk.abac.PolicySqlCompiler;
import io.saasforge.sdk.abac.SqlPredicate;
import io.saasforge.sdk.core.MembershipGrants;
import io.saasforge.sdk.core.api.ForgeException;
import io.saasforge.sdk.core.api.FrameworkErrorCode;
import io.saasforge.sdk.security.MembershipGrantStore;
import io.saasforge.sdk.security.TenantContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * 当前成员对 Data Resource 的数据权限（ADR-0006）：SQL 拦截器据此过滤列表、更新与删除，业务代码据此校验单个对象。
 *
 * <p>策略来源：当前 Membership 各 Role 上该资源的 Data Policy，经 iam 随权限明细按版本发布，多个 Role 取并集。规则：
 *
 * <ul>
 *   <li>成员请求（Tenant Realm Token）：按策略过滤；没有任何策略的资源默认拒绝，看不到、改不了任何数据。</li>
 *   <li>其他已认证请求（如 Platform Realm Token）：一律拒绝。TODO(#22): 平台侧权限接入后改为按 Operator 的策略判断。</li>
 *   <li>没有认证上下文的系统操作（MQ 消费、定时任务、租户清理）：不受数据权限限制，仍受租户隔离。</li>
 * </ul>
 *
 * <p>{@link #permits} 用内存求值，SQL 拦截器用同一棵表达式树编译谓词，两者对同一对象的结论一致。
 */
public final class DataPermission {
    private static final Logger log = LoggerFactory.getLogger(DataPermission.class);
    // 解析结果按资源与 JSON 缓存；预设只有少数几种，上限只防自定义策略过多时无限增长。
    private static final int MAX_CACHED_POLICIES = 1_000;

    private final Map<String, DataResourceDefinition> byCode;
    private final Map<String, DataResourceDefinition> byTable;
    private final MembershipGrantStore grants;
    private final Map<String, Expression> parsed = new ConcurrentHashMap<>();

    /**
     * 创建数据权限。
     *
     * @param resources 本服务声明的数据资源
     * @param grants 读取当前 Membership 的明细；非 Web 应用或未配置 Redis 时为 null，此时成员请求无法判断数据权限
     */
    public DataPermission(Collection<DataResourceDefinition> resources, MembershipGrantStore grants) {
        this.byCode = resources.stream()
                .collect(Collectors.toUnmodifiableMap(DataResourceDefinition::code, Function.identity()));
        this.byTable = resources.stream()
                .collect(Collectors.toUnmodifiableMap(DataResourceDefinition::table, Function.identity()));
        this.grants = grants;
    }

    /**
     * 当前请求是否可以访问资源对象。
     *
     * @param resource Data Resource 编码，如 {@code order}
     * @param attributes 对象的资源属性，键为属性编码（如 {@code ownerId}），未给出的属性视为 null
     * @return 系统操作恒为 {@code true}；成员请求按策略判断；其他已认证请求为 {@code false}
     * @throws IllegalArgumentException 资源未声明，或给出了资源未声明的属性
     */
    public boolean permits(String resource, Map<String, ?> attributes) {
        DataResourceDefinition definition = definition(resource);
        Map<String, Object> values = definition.resourceValues(attributes);
        Subject subject = currentSubjectOf();
        if (subject == null) return true;
        return PolicyEvaluator.isSatisfied(policy(subject, definition), subject.context(), values);
    }

    /**
     * 要求当前请求可以访问资源对象，如创建或修改对象后的新状态仍在自己的数据范围内。
     *
     * @param resource Data Resource 编码
     * @param attributes 对象的资源属性，键为属性编码
     * @throws ForgeException 403 {@code auth.forbidden}
     * @throws IllegalArgumentException 资源未声明，或给出了资源未声明的属性
     */
    public void check(String resource, Map<String, ?> attributes) {
        if (!permits(resource, attributes)) {
            throw ForgeException.withErrorMessage(
                    FrameworkErrorCode.AUTH_FORBIDDEN, "Data Resource " + resource + " is outside the data scope");
        }
    }

    /**
     * 当前成员的主体属性与环境属性，如创建对象时以当前成员与其主部门作为数据归属。
     *
     * @return 键为全名的上下文（如 {@code subject.membershipId}、{@code env.now}）；不是成员请求时为空
     */
    public Optional<Map<String, Object>> currentSubject() {
        Subject subject = currentSubjectOf();
        return subject == null || subject.grants() == null ? Optional.empty() : Optional.of(subject.context());
    }

    /** 表名（小写）对应的数据资源；SQL 拦截器据此判断语句是否涉及数据资源。 */
    Map<String, DataResourceDefinition> byTable() {
        return byTable;
    }

    /**
     * 当前请求的主体；没有认证上下文的系统操作为 null。
     *
     * <p>非成员的已认证请求得到没有明细的主体，其策略恒为假。
     *
     * @throws IllegalStateException 成员请求但服务没有明细来源（未配置 Redis）：以 500 暴露配置错误，不静默放行
     */
    Subject currentSubjectOf() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwt)) return null;
        if (jwt.getToken().getClaimAsString("membership_id") == null) return new Subject(Map.of(), null);
        if (grants == null) throw new IllegalStateException("Data permissions require Redis for Membership grants");
        TenantContext context = TenantContext.current();
        MembershipGrants current = grants.grants(context);
        Map<String, Object> values = current.subject().toContext(context.membershipId());
        values.putAll(DataResourceDefinition.environment(Instant.now()));
        return new Subject(values, current);
    }

    private DataResourceDefinition definition(String resource) {
        DataResourceDefinition definition = byCode.get(resource);
        if (definition == null) throw new IllegalArgumentException("Undeclared Data Resource: " + resource);
        return definition;
    }

    private Expression parse(DataResourceDefinition definition, String json, Map<String, String> extendedTypes) {
        // 同一条策略在扩展属性类型不同的租户中结论不同，类型要进缓存键
        String key = definition.code() + '\u0000' + json + '\u0000' + new TreeMap<>(extendedTypes);
        Expression cached = parsed.get(key);
        if (cached != null) return cached;
        Expression expression = definition.parse(json, extendedTypes);
        if (parsed.size() >= MAX_CACHED_POLICIES) parsed.clear();
        parsed.put(key, expression);
        return expression;
    }

    /**
     * 一次请求中的主体。
     *
     * @param context 主体与环境上下文，同一请求内的求值与编译取同一份
     * @param grants 明细；非成员请求为 null
     */
    record Subject(Map<String, Object> context, MembershipGrants grants) {
        Subject {
            context = Map.copyOf(context);
        }
    }

    /** 主体在资源上的有效策略：各 Role 策略的并集，没有策略时恒为假。 */
    Expression policy(Subject subject, DataResourceDefinition definition) {
        if (subject.grants() == null) return Expression.union(List.of());
        var policies = new ArrayList<Expression>();
        for (String json : subject.grants().dataPolicies().getOrDefault(definition.code(), List.of())) {
            try {
                policies.add(parse(definition, json, subject.grants().subject().attributeTypes()));
            } catch (IllegalArgumentException error) {
                // 资源在新版本中删除了策略引用的属性、扩展属性定义已删除等情况：该条策略按拒绝处理并记录，
                // 不放行，也不让整个请求失败。
                log.error("Ignored invalid Data Policy on {}: {}", definition.code(), error.getMessage());
            }
        }
        return Expression.union(policies);
    }

    /** 编译主体在资源上的 SQL 谓词。 */
    SqlPredicate compile(Subject subject, DataResourceDefinition definition, String qualifier) {
        return PolicySqlCompiler.compile(policy(subject, definition), subject.context(), definition.columns(qualifier));
    }

    static String normalizeTable(String name) {
        String table = name.substring(name.lastIndexOf('.') + 1);
        return table.replace("\"", "").toLowerCase(Locale.ROOT);
    }
}
