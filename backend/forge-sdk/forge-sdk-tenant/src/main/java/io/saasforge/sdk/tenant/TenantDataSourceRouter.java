package io.saasforge.sdk.tenant;

import java.util.UUID;
import javax.sql.DataSource;

/**
 * 「当前租户 → 数据源」扩展点，为将来大客户独立库预留（ADR-0001）。
 *
 * <p>第一期只有 {@link SingleDataSourceRouter}；应用声明本接口的 Bean 即可替换。无租户上下文的连接
 * 始终取自应用的主数据源。
 */
@FunctionalInterface
public interface TenantDataSourceRouter {
    /**
     * 选择租户数据所在的数据源。
     *
     * @param tenantId 当前租户
     * @return 承载该租户数据的数据源，SDK 会在取到的连接上设置 RLS 会话变量
     */
    DataSource route(UUID tenantId);
}
