package io.saasforge.sdk.tenant;

import com.baomidou.mybatisplus.core.plugins.InterceptorIgnoreHelper;
import com.baomidou.mybatisplus.extension.parser.JsqlParserGlobal;
import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import io.saasforge.sdk.core.CurrentTenant;
import io.saasforge.sdk.core.api.ForgeException;
import io.saasforge.sdk.core.api.FrameworkErrorCode;
import java.util.Set;
import java.util.UUID;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;

/** {@link TenantWriteGuard} 的实现：在 INSERT、UPDATE、DELETE 执行前检查当前租户的状态。 */
final class TenantWriteGuardInnerInterceptor implements InnerInterceptor {
    private final CurrentTenantLineHandler tables;
    private final TenantStatusLookup statuses;

    TenantWriteGuardInnerInterceptor(CurrentTenantLineHandler tables, TenantStatusLookup statuses) {
        this.tables = tables;
        this.statuses = statuses;
    }

    @Override
    public void beforeUpdate(Executor executor, MappedStatement ms, Object parameter) {
        if (TenantWriteGuard.exempted() || InterceptorIgnoreHelper.willIgnoreTenantLine(ms.getId())) {
            return;
        }
        UUID tenantId = CurrentTenant.find().orElse(null);
        if (tenantId == null || onlyGlobalTables(ms.getBoundSql(parameter).getSql())) {
            return;
        }
        String status = statuses.status(tenantId);
        if (status != null && !TenantWriteGuard.WRITABLE.contains(status)) {
            throw ForgeException.withErrorMessage(
                    FrameworkErrorCode.TENANT_WRITE_BLOCKED,
                    "Tenant " + tenantId + " is " + status + ", writes are blocked");
        }
    }

    // 解析失败时按租户表处理：宁可多查一次状态，也不漏过写入。
    private boolean onlyGlobalTables(String sql) {
        Set<String> names;
        try {
            names = new TablesNamesFinder<Void>().getTables(JsqlParserGlobal.parse(sql));
        } catch (JSQLParserException e) {
            return false;
        }
        return !names.isEmpty() && names.stream().allMatch(tables::ignoreTable);
    }
}
