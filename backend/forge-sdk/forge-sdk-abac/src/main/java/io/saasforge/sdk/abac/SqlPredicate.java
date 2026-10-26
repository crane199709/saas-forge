package io.saasforge.sdk.abac;

import java.util.List;

/**
 * 编译得到的参数化 SQL 谓词，可直接放入 {@code WHERE}。
 *
 * @param sql 只含列引用、运算符与 {@code ?} 占位符的谓词，不含任何用户值
 * @param parameters 按占位符顺序排列的参数
 */
public record SqlPredicate(String sql, List<SqlParameter> parameters) {
    /** 拷贝为不可变列表。 */
    public SqlPredicate {
        parameters = List.copyOf(parameters);
    }
}
