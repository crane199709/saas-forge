package io.saasforge.sdk.tenant;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import io.saasforge.sdk.core.CurrentActorProvider;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 为 {@link AuditableEntity} 填充标准列。
 *
 * <p>直接 {@code setValue} 强制覆盖：MyBatis-Plus 的 {@code strictInsertFill}/{@code strictUpdateFill} 只填 null 字段，
 * 先查后改的实体带着旧值，修改时间与修改人就不会刷新；{@code setFieldValByName} 则跳过 null，操作人为空时留下调用方的值。
 */
final class AuditColumnsMetaObjectHandler implements MetaObjectHandler {
    private final ObjectProvider<CurrentActorProvider> actors;

    AuditColumnsMetaObjectHandler(ObjectProvider<CurrentActorProvider> actors) {
        this.actors = actors;
    }

    @Override
    public void insertFill(MetaObject metaObject) {
        if (!(metaObject.getOriginalObject() instanceof AuditableEntity)) {
            return;
        }
        // updated_at 与 created_at 取同一时刻，新建的行不会显示为「已修改」
        OffsetDateTime now = now();
        UUID actor = currentActor();
        metaObject.setValue("createdAt", now);
        metaObject.setValue("createdBy", actor);
        metaObject.setValue("updatedAt", now);
        metaObject.setValue("updatedBy", actor);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        if (!(metaObject.getOriginalObject() instanceof AuditableEntity)) {
            return;
        }
        metaObject.setValue("updatedAt", now());
        metaObject.setValue("updatedBy", currentActor());
    }

    // 与 timestamptz 的微秒精度一致，写入后实体中的值与重新读出的值相等
    private static OffsetDateTime now() {
        return OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);
    }

    private UUID currentActor() {
        CurrentActorProvider provider = actors.getIfAvailable();
        return provider == null ? null : provider.currentActor().orElse(null);
    }
}
