package io.saasforge.sdk.tenant;

import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/** 默认实现：所有租户共享同一数据源，靠 {@code tenant_id} 与 RLS 隔离。 */
public final class SingleDataSourceRouter implements TenantDataSourceRouter {
    private final DataSource dataSource;

    /**
     * 创建路由。
     *
     * @param dataSource 所有租户共享的数据源
     */
    public SingleDataSourceRouter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /** 任何租户都返回共享数据源。 */
    @Override
    public DataSource route(UUID tenantId) {
        return dataSource;
    }
}
