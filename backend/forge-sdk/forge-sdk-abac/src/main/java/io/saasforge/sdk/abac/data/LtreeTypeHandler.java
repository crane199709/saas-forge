package io.saasforge.sdk.abac.data;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

/**
 * 把 {@link String} 写入 PostgreSQL {@code ltree} 列，如 Data Resource 的归属部门路径。
 *
 * <p>按 varchar 绑定会被 PostgreSQL 拒绝（列类型为 ltree），这里以未指定类型绑定，由服务端推断为列类型。
 * 读取时 ltree 直接转为文本，不需要本处理器。用法：{@code @TableField(typeHandler = LtreeTypeHandler.class)}。
 */
public final class LtreeTypeHandler extends BaseTypeHandler<String> {
    /** 以未指定类型绑定路径文本。 */
    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, String parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setObject(i, parameter, Types.OTHER);
    }

    /** 按列名读取路径文本。 */
    @Override
    public String getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return rs.getString(columnName);
    }

    /** 按列序号读取路径文本。 */
    @Override
    public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return rs.getString(columnIndex);
    }

    /** 读取存储过程的路径输出参数。 */
    @Override
    public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return cs.getString(columnIndex);
    }
}
