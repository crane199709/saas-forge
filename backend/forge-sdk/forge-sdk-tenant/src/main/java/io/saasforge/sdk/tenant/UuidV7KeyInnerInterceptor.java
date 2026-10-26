package io.saasforge.sdk.tenant;

import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import io.saasforge.sdk.core.UuidV7;
import java.util.UUID;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.reflection.SystemMetaObject;

/**
 * 插入实体时为空的 {@link UUID} 主键生成 UUIDv7（ADR-0014）。
 *
 * <p>MyBatis-Plus 的 {@code ASSIGN_UUID} 只支持 {@code String} 主键，且默认的 {@code ASSIGN_ID} 生成雪花 long，
 * 因此在 MyBatis-Plus 填充主键之前由本拦截器赋值；已赋值的主键保持不变。
 */
public final class UuidV7KeyInnerInterceptor implements InnerInterceptor {
    /** 只处理以实体本身为参数的插入，如 {@code BaseMapper#insert(Object)}。 */
    @Override
    public void beforeUpdate(Executor executor, MappedStatement ms, Object parameter) {
        if (ms.getSqlCommandType() != SqlCommandType.INSERT || parameter == null) {
            return;
        }
        TableInfo table = TableInfoHelper.getTableInfo(parameter.getClass());
        if (table == null || table.getKeyType() != UUID.class) {
            return;
        }
        var entity = SystemMetaObject.forObject(parameter);
        if (entity.getValue(table.getKeyProperty()) == null) {
            entity.setValue(table.getKeyProperty(), UuidV7.generate());
        }
    }
}
