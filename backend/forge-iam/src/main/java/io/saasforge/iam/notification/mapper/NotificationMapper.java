package io.saasforge.iam.notification.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import io.saasforge.iam.notification.model.NotificationResponse;
import io.saasforge.sdk.notification.NotificationSent;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** Notification 非标准表通过显式租户条件与事务内 RLS 绑定隔离，不受 Data Policy 过滤。 */
@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface NotificationMapper {
    /** 在当前事务绑定可信租户，事务结束后自动清除，防止连接复用残留租户。 */
    String bindTenant(@Param("tenantId") UUID tenantId);
    /** 确保UTC月份的内容与收件分区存在。 */
    void ensurePartition(@Param("month") LocalDate month);
    /** 首次收件时保存内容，重复事件返回0，防止重投改变收件快照。 */
    int insert(@Param("event") NotificationSent event);
    /** 为首次接收的通知保存本租户收件Membership。 */
    void addRecipient(@Param("event") NotificationSent event, @Param("membershipId") UUID membershipId);
    /** 当前租户与Membership的一页收件箱，按发生时间与ID倒序。 */
    List<NotificationResponse> page(
            @Param("tenantId") UUID tenantId,
            @Param("membershipId") UUID membershipId,
            @Param("offset") long offset,
            @Param("limit") int limit);
    /** 当前租户与Membership的通知总数。 */
    long count(@Param("tenantId") UUID tenantId, @Param("membershipId") UUID membershipId);
    /** 当前Membership的未读数。 */
    long unreadCount(@Param("tenantId") UUID tenantId, @Param("membershipId") UUID membershipId);
    /** 幂等标记当前收件人的一条或全部通知已读；id为NULL时全读，已读时间不刷新。 */
    int markRead(@Param("tenantId") UUID tenantId, @Param("membershipId") UUID membershipId, @Param("id") UUID id);
    /** 给定通知是否属于当前租户的收件Membership。 */
    boolean exists(@Param("tenantId") UUID tenantId, @Param("membershipId") UUID membershipId, @Param("id") UUID id);
    /** ADR-0022非标准收件记录按指定租户物理清理，不受当前租户上下文改写。 */
    void deleteRecipients(@Param("tenantId") UUID tenantId);
    /** ADR-0022非标准通知内容按指定租户物理清理。 */
    void deleteNotifications(@Param("tenantId") UUID tenantId);
    /** 预建当前及下月分区并删除UTC日期下已整月满180天的分区，跨租户维护。 */
    void maintainPartitions(@Param("today") LocalDate today);
}
