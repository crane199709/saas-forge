package io.saasforge.iam.todo.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import io.saasforge.iam.todo.model.TodoRow;
import io.saasforge.iam.todo.model.TodoVisibility;
import io.saasforge.sdk.notification.TodoClosed;
import io.saasforge.sdk.notification.TodoOpened;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** SQL 显式限定租户；RLS 仍使用事件或已验证请求的租户上下文兜底。 */
@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface TodoMapper {
    /** 只采用更晚的开启事件；返回获得行锁的待办 ID，旧事件返回 null。 */
    UUID open(
            @Param("id") UUID id,
            @Param("event") TodoOpened event,
            @Param("second") Instant second,
            @Param("nanos") int nanos);
    /** 只采用更晚关闭事件，先到的关闭也落墓碑，返回实际写入行数。 */
    int close(
            @Param("id") UUID id,
            @Param("event") TodoClosed event,
            @Param("second") Instant second,
            @Param("nanos") int nanos);
    /** 更晚开启事件更换处理目标，调用方已持有待办行锁。 */
    void clearMemberships(@Param("tenantId") UUID tenantId, @Param("todoId") UUID todoId);
    /** 插入经所属租户校验的去重处理人。 */
    void addMemberships(@Param("tenantId") UUID tenantId, @Param("todoId") UUID todoId, @Param("ids") List<UUID> ids);
    /** 当前租户指定成员的待办，按状态发生时间、ID 倒序分页。 */
    List<TodoRow> page(
            @Param("visibility") TodoVisibility visibility, @Param("offset") long offset, @Param("limit") int limit);
    /** 删除租户的纯处理人关联，清理后不保留成员标识。 */
    void clearTenantMemberships(@Param("tenantId") UUID tenantId);
    /** 冷静期结束后逻辑删除租户的全部待办状态，标准列记为系统操作。 */
    void markTenantDeleted(@Param("tenantId") UUID tenantId);
    /** 与分页使用相同的可见性条件。 */
    long count(@Param("visibility") TodoVisibility visibility);
}
