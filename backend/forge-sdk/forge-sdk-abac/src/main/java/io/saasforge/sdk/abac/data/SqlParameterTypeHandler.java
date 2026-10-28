package io.saasforge.sdk.abac.data;

import io.saasforge.sdk.abac.AttributeType;
import io.saasforge.sdk.abac.SqlParameter;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;

/** 绑定编译器输出的 {@link SqlParameter}：SQL 中已写明 {@code CAST(? AS <type>)}，这里只需按单值或数组设置参数。 */
final class SqlParameterTypeHandler implements TypeHandler<SqlParameter> {
    @Override
    public void setParameter(PreparedStatement ps, int i, SqlParameter parameter, JdbcType jdbcType)
            throws SQLException {
        if (parameter.array()) {
            // 驱动不认识 ltree 元素类型，以 text[] 传入，再由 SQL 中的 CAST 转为 ltree[]
            String elementType = parameter.type() == AttributeType.LTREE
                    ? "text"
                    : parameter.type().sqlType();
            ps.setArray(i, ps.getConnection().createArrayOf(elementType, ((List<?>) parameter.value()).toArray()));
        } else if (parameter.value() == null) {
            ps.setNull(i, Types.VARCHAR);
        } else {
            ps.setObject(i, parameter.value());
        }
    }

    @Override
    public SqlParameter getResult(ResultSet rs, String columnName) {
        throw new UnsupportedOperationException("Data Policy parameters are never read back");
    }

    @Override
    public SqlParameter getResult(ResultSet rs, int columnIndex) {
        throw new UnsupportedOperationException("Data Policy parameters are never read back");
    }

    @Override
    public SqlParameter getResult(CallableStatement cs, int columnIndex) {
        throw new UnsupportedOperationException("Data Policy parameters are never read back");
    }
}
