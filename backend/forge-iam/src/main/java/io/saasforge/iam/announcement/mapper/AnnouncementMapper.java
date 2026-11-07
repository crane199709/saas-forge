package io.saasforge.iam.announcement.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import io.saasforge.iam.announcement.model.AnnouncementView;
import io.saasforge.iam.announcement.model.PlatformAnnouncementRow;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

// IAM 同时承载两个 Realm；显式租户条件与事务内 RLS 绑定不依赖租户 HTTP 过滤器。
@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface AnnouncementMapper {
    /** 在当前事务设置可信租户，RLS 将与显式租户条件共同隔离公告。 */
    String bindTenant(@Param("tenantId") UUID tenantId);
    /** 发布本租户公告，并维护标准审计列。 */
    void publishTenant(
            @Param("id") UUID id,
            @Param("tenantId") UUID tenantId,
            @Param("actor") UUID actor,
            @Param("title") String title,
            @Param("body") String body,
            @Param("subApp") String subApp,
            @Param("route") String route,
            @Param("expiresAt") Instant expiresAt);
    /** 当前 Membership 可见的公告；已到期、撤回或删除的公告被排除。 */
    List<AnnouncementView> visible(@Param("tenantId") UUID tenantId, @Param("membershipId") UUID membershipId);
    /** 记录当前 Membership 的已读状态，重复读取幂等。 */
    void markRead(
            @Param("id") UUID id,
            @Param("tenantId") UUID tenantId,
            @Param("membershipId") UUID membershipId,
            @Param("actor") UUID actor,
            @Param("announcementId") UUID announcementId);
    /** 发布平台公告，目标规则保持原样以覆盖生效期内新租户。 */
    void publishPlatform(
            @Param("id") UUID id,
            @Param("actor") UUID actor,
            @Param("title") String title,
            @Param("body") String body,
            @Param("subApp") String subApp,
            @Param("route") String route,
            @Param("expiresAt") Instant expiresAt,
            @Param("allTenants") boolean allTenants);
    /** 将指定租户加入平台公告的固定目标列表。 */
    void target(@Param("announcementId") UUID announcementId, @Param("tenantId") UUID tenantId);
    /** 平台管理列表，包括已到期和撤回的公告。 */
    List<PlatformAnnouncementRow> platformList();
    /** 指定平台公告的租户目标，全部租户公告为空。 */
    List<UUID> targets(@Param("id") UUID id);
    /** 本租户管理列表，包含已到期和已撤回的公告。 */
    List<AnnouncementView> tenantList(@Param("tenantId") UUID tenantId);
    /** 撤回本租户公告，重复调用不重复改变状态或审计。 */
    int revokeTenant(@Param("tenantId") UUID tenantId, @Param("id") UUID id, @Param("actor") UUID actor);
    /** 撤回平台公告，不接受租户公告标识。 */
    int revokePlatform(@Param("id") UUID id, @Param("actor") UUID actor);
    /** 租户清理时逻辑删除该租户的公告内容。 */
    void deleteTenantAnnouncements(@Param("tenantId") UUID tenantId);
    /** 租户清理时逻辑删除该租户所有 Membership 的已读状态。 */
    void deleteTenantReads(@Param("tenantId") UUID tenantId);
    /** 清理平台公告的指定租户关联，保留其他目标和全租户公告。 */
    void deleteTenantTargets(@Param("tenantId") UUID tenantId);
}
