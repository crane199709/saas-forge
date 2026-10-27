package io.saasforge.gateway.ratelimit.service;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowItem;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRuleManager;
import io.saasforge.gateway.ratelimit.config.TenantRateLimitProperties;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * 以 Sentinel 热点参数规则按租户限流：同一资源、以租户 ID 为参数，每个租户各自计数，一个租户超限不占用其他租户的额度。
 *
 * <p>Sentinel 的规则是进程级静态状态，启动时按配置加载一次。
 */
@Service
public class TenantRateLimiter {
    /** 计数窗口：每个租户的额度按秒恢复，被限流的请求据此提示多久后重试。 */
    public static final int WINDOW_SECONDS = 1;

    static final String RESOURCE = "forge-gateway:tenant";

    TenantRateLimiter(TenantRateLimitProperties properties, ObjectProvider<TenantQpsOverrides> overrides) {
        Map<UUID, Integer> qpsByTenant = overrides.getIfAvailable(() -> Map::of).qpsByTenant();
        ParamFlowRule rule = new ParamFlowRule(RESOURCE)
                .setParamIdx(0)
                .setGrade(RuleConstant.FLOW_GRADE_QPS)
                .setCount(properties.defaultQps())
                .setDurationInSec(WINDOW_SECONDS)
                .setParamFlowItemList(qpsByTenant.entrySet().stream()
                        .map(item ->
                                new ParamFlowItem(item.getKey().toString(), item.getValue(), String.class.getName()))
                        .toList());
        ParamFlowRuleManager.loadRules(List.of(rule));
    }

    /**
     * 为租户占用一次请求额度。
     *
     * @param tenantId 已验签 Token 中的租户
     * @return 未超过阈值时为 true
     */
    public boolean tryAcquire(UUID tenantId) {
        try {
            Entry entry = SphU.entry(RESOURCE, EntryType.IN, 1, tenantId.toString());
            // 只按 QPS 计数，不统计并发与耗时，取得额度后立即释放
            entry.exit(1, tenantId.toString());
            return true;
        } catch (BlockException e) {
            return false;
        }
    }
}
