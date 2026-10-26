package io.saasforge.sdk.tenant;

import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import io.saasforge.sdk.core.CurrentTenant;
import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.StringValue;

/** 为 {@code TenantLineInnerInterceptor} 提供当前租户，并排除声明为全局的表。 */
public final class CurrentTenantLineHandler implements TenantLineHandler {
    private final Set<String> ignoreTables;

    /**
     * 创建处理器。
     *
     * @param ignoreTables 不按租户隔离的全局表名
     */
    public CurrentTenantLineHandler(Collection<String> ignoreTables) {
        this.ignoreTables =
                ignoreTables.stream().map(CurrentTenantLineHandler::normalize).collect(Collectors.toSet());
    }

    /**
     * 返回当前租户字面量；PostgreSQL 会把未定类型的字面量隐式转为 {@code uuid}。
     *
     * @throws IllegalStateException 无租户上下文时，阻止 SQL 以无租户身份执行
     */
    @Override
    public Expression getTenantId() {
        return new StringValue(CurrentTenant.require().toString());
    }

    /** 全局表不注入租户条件，也不会触发租户上下文检查。 */
    @Override
    public boolean ignoreTable(String tableName) {
        return ignoreTables.contains(normalize(tableName));
    }

    private static String normalize(String tableName) {
        String name = tableName.substring(tableName.lastIndexOf('.') + 1);
        return name.replace("\"", "").toLowerCase(Locale.ROOT);
    }
}
