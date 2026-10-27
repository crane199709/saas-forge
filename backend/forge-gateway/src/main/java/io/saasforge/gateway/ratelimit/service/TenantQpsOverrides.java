package io.saasforge.gateway.ratelimit.service;

import java.util.Map;
import java.util.UUID;

/**
 * 为个别租户设置不同限流阈值的扩展点，预留给按套餐配置阈值。第一期不提供实现，所有租户使用默认阈值；声明一个该类型的 Bean 即生效。
 */
@FunctionalInterface
public interface TenantQpsOverrides {

    /**
     * 需要使用非默认阈值的租户。
     *
     * @return 租户 ID 到每秒允许请求数的映射，未列出的租户使用默认阈值
     */
    Map<UUID, Integer> qpsByTenant();
}
