package io.saasforge.iam.notification.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 测试专用数据库边界：故意省略租户 SQL 条件，验证应用角色与父表、子分区的 RLS。 */
@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface NotificationIsolationProbe {
    /** 确认探针以无超级用户或 BYPASSRLS 权限的 iam_app 运行。 */
    boolean isRestrictedRuntimeRole();

    /** 只在当前探针事务绑定租户。 */
    String bindTenant(@Param("tenantId") UUID tenantId);

    /** 不带租户条件读取内容；directPartition 选择测试时钟下固定的二月子分区。 */
    List<UUID> notificationTenants(@Param("directPartition") boolean directPartition);

    /** 不带租户条件读取收件人，覆盖父表及固定子分区。 */
    List<UUID> recipientTenants(@Param("directPartition") boolean directPartition);

    /** 尝试向父表或固定子分区写入给定租户的内容，以验证 WITH CHECK。 */
    void insertNotification(
            @Param("id") UUID id,
            @Param("tenantId") UUID tenantId,
            @Param("occurredAt") Instant occurredAt,
            @Param("directPartition") boolean directPartition);

    /** 尝试向父表或固定子分区写入给定租户的收件快照，以验证 WITH CHECK。 */
    void insertRecipient(
            @Param("id") UUID id,
            @Param("tenantId") UUID tenantId,
            @Param("membershipId") UUID membershipId,
            @Param("occurredAt") Instant occurredAt,
            @Param("directPartition") boolean directPartition);
}
