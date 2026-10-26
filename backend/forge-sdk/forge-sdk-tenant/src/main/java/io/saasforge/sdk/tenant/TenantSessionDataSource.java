package io.saasforge.sdk.tenant;

import io.saasforge.sdk.core.CurrentTenant;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * 每次取连接时把当前租户写入会话变量 {@code app.tenant_id}，供业务表的 RLS 策略兜底（ADR-0001）。
 *
 * <p>即使有人绕过 MyBatis-Plus 直接执行 SQL，连接上的会话变量仍限定可见行。无租户上下文时把变量置空：
 * 连接池会复用连接，不清空就会沿用上一个借用者的租户。
 */
public final class TenantSessionDataSource extends DelegatingDataSource {
    /** RLS 策略读取的会话变量名，业务表迁移脚本中的策略必须使用同一名称。 */
    public static final String SESSION_VARIABLE = "app.tenant_id";

    private static final String SET_TENANT = "SELECT set_config('" + SESSION_VARIABLE + "', ?, false)";

    private final TenantDataSourceRouter router;

    /**
     * 包装应用的主数据源。
     *
     * @param defaultDataSource 主数据源，无租户上下文时使用
     * @param router 有租户上下文时选择数据源
     */
    public TenantSessionDataSource(DataSource defaultDataSource, TenantDataSourceRouter router) {
        super(defaultDataSource);
        this.router = router;
    }

    /** 按当前租户选择数据源并设置会话变量。 */
    @Override
    public Connection getConnection() throws SQLException {
        Optional<UUID> tenantId = CurrentTenant.find();
        DataSource target = tenantId.map(router::route).orElse(obtainTargetDataSource());
        return withTenant(target.getConnection(), tenantId);
    }

    /** 与 {@link #getConnection()} 相同，只是使用指定账号；会话变量照常设置。 */
    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        Optional<UUID> tenantId = CurrentTenant.find();
        DataSource target = tenantId.map(router::route).orElse(obtainTargetDataSource());
        return withTenant(target.getConnection(username, password), tenantId);
    }

    /**
     * {@code unwrap(DataSource.class)} 返回底层连接池，其余接口按 {@link DelegatingDataSource} 处理。
     *
     * <p>Spring Boot 推导 Flyway 迁移账号的连接时会一路 unwrap 到底层连接池读取 URL 与驱动；返回自身会导致推导失败。
     * 直接从底层连接池取连接不设置会话变量，可能残留上一个借用者的租户，业务代码不得这样使用。
     */
    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        return iface == DataSource.class ? iface.cast(obtainTargetDataSource()) : super.unwrap(iface);
    }

    private static Connection withTenant(Connection connection, Optional<UUID> tenantId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SET_TENANT)) {
            statement.setString(1, tenantId.map(UUID::toString).orElse(""));
            statement.execute();
            return connection;
        } catch (SQLException | RuntimeException error) {
            connection.close();
            throw error;
        }
    }
}
