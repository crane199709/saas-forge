package io.saasforge.sdk.tenant;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

/** MyBatis 没有内置 {@link UUID} 类型处理器；PostgreSQL 驱动原生支持 {@code uuid} 与 {@link UUID} 互转。 */
public final class UuidTypeHandler extends BaseTypeHandler<UUID> {
    /** 按 {@code uuid} 类型绑定参数。 */
    @Override
    public void setNonNullParameter(PreparedStatement statement, int index, UUID parameter, JdbcType jdbcType)
            throws SQLException {
        statement.setObject(index, parameter);
    }

    /** 按列名读取 {@code uuid} 列。 */
    @Override
    public UUID getNullableResult(ResultSet result, String columnName) throws SQLException {
        return result.getObject(columnName, UUID.class);
    }

    /** 按列序号读取 {@code uuid} 列。 */
    @Override
    public UUID getNullableResult(ResultSet result, int columnIndex) throws SQLException {
        return result.getObject(columnIndex, UUID.class);
    }

    /** 读取存储过程的 {@code uuid} 输出参数。 */
    @Override
    public UUID getNullableResult(CallableStatement statement, int columnIndex) throws SQLException {
        return statement.getObject(columnIndex, UUID.class);
    }
}
