package io.saasforge.iam.membership.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import io.saasforge.iam.membership.model.Invitation;
import io.saasforge.iam.membership.model.MemberContactRow;
import io.saasforge.iam.membership.model.MemberRow;
import io.saasforge.iam.membership.model.MembershipChange;
import io.saasforge.iam.membership.model.MembershipStatus;
import io.saasforge.iam.membership.model.MembershipVersion;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

// iam 的表不按租户行拦截隔离（ADR-0001），租户条件由 SQL 显式书写；消费事件时 SDK 恢复的租户上下文不能改写这些 SQL。
@InterceptorIgnore(tenantLine = "true")
@Mapper
public interface MembershipMapper {
    /** 停用 Membership 并递增版本号，返回新版本；不存在或已删除时返回 null。管理操作按主键定位，不受 Data Policy 过滤。 */
    MembershipChange deactivate(@Param("membershipId") UUID membershipId);

    /** 递增版本号（如角色变更），返回新版本；不存在或已删除时返回 null。不受 Data Policy 过滤。 */
    MembershipChange incrementVersion(@Param("membershipId") UUID membershipId);

    /** 递增租户内全部 active Membership 的版本号（如租户 Plan 变更），返回各自的新版本。按参数中的租户限定，不受 Data Policy 过滤。 */
    List<MembershipVersion> incrementActiveVersions(@Param("tenantId") UUID tenantId);

    /** 未删除的 Membership 所属的租户；不存在时为 null。不受 Data Policy 过滤。 */
    Object tenantOf(@Param("membershipId") UUID membershipId);

    /**
     * 为身份建立 invited 的 Membership，已有未删除的 Membership（不论状态）时不做修改。{@code actor} 为 null 表示系统（开通）。
     * 不受 Data Policy 过滤。
     */
    void insertInvited(
            @Param("id") UUID id,
            @Param("identityId") UUID identityId,
            @Param("tenantId") UUID tenantId,
            @Param("actor") UUID actor);

    /** 已离开的 Membership 重新改为 invited，返回受影响行数。按 Token 租户限定，不受 Data Policy 过滤。 */
    int reinvite(
            @Param("tenantId") UUID tenantId, @Param("membershipId") UUID membershipId, @Param("actor") UUID actor);

    /** 租户内未删除 Membership 的状态；不存在时为 null。按 Token 租户限定，不受 Data Policy 过滤。 */
    String status(@Param("tenantId") UUID tenantId, @Param("membershipId") UUID membershipId);

    /**
     * 状态从 {@code from} 改为 {@code to} 并递增版本，返回新版本；当前状态不是 {@code from} 或不存在时为 null。
     * 按 Token 租户限定，不受 Data Policy 过滤。
     */
    MembershipChange transition(
            @Param("tenantId") UUID tenantId,
            @Param("membershipId") UUID membershipId,
            @Param("from") String from,
            @Param("to") String to,
            @Param("actor") UUID actor);

    /**
     * 未离开的 Membership 改为 left、清空 Extended Attribute 值并递增版本，返回新版本；已离开或不存在时为 null。
     * 按 Token 租户限定，不受 Data Policy 过滤。
     */
    MembershipChange leave(
            @Param("tenantId") UUID tenantId, @Param("membershipId") UUID membershipId, @Param("actor") UUID actor);

    /** 租户内 active 的 Membership 是否存在，并对该行加共享锁至事务结束。按 Token 租户限定，不受 Data Policy 过滤。 */
    boolean lockActive(@Param("tenantId") UUID tenantId, @Param("membershipId") UUID membershipId);

    /**
     * 成员管理列表的一页，按 Membership 标识排序；含已离开的成员。不受 Data Policy 过滤。
     *
     * @param keyword 在账号、昵称、邮箱、手机号中按子串匹配（不区分大小写），为 null 时不过滤
     * @param status Membership 状态，为 null 时不过滤
     */
    List<MemberContactRow> search(
            @Param("tenantId") UUID tenantId,
            @Param("keyword") String keyword,
            @Param("status") String status,
            @Param("offset") long offset,
            @Param("limit") int limit);

    /** 与 {@link #search} 条件相同的成员数。不受 Data Policy 过滤。 */
    long countSearch(
            @Param("tenantId") UUID tenantId, @Param("keyword") String keyword, @Param("status") String status);

    /** 身份在租户内未删除的 Membership；不存在时为 null。不受 Data Policy 过滤。 */
    MembershipStatus findStatus(@Param("identityId") UUID identityId, @Param("tenantId") UUID tenantId);

    /** 邀请涉及的 Membership、身份与租户，三者均未删除；不存在时为 null。按邀请令牌定位，不受 Data Policy 过滤。 */
    Invitation findInvitation(@Param("membershipId") UUID membershipId);

    /** 接受邀请：invited 改为 active，返回受影响行数；已接受或不存在时为 0。没有认证上下文，不受 Data Policy 过滤。 */
    int activateInvited(@Param("membershipId") UUID membershipId);

    /**
     * 租户内未删除、未离开的 Membership 是否存在，并对该行加共享锁至事务结束，与并发的移除串行。
     * 按 Token 租户限定，不受 Data Policy 过滤。
     */
    boolean exists(@Param("tenantId") UUID tenantId, @Param("membershipId") UUID membershipId);

    /**
     * 租户内一页未删除、未离开的 Membership 及其 Identity 资料，按 Membership 标识（UUIDv7，即大致的创建顺序）排序。
     * 不受 Data Policy 过滤。
     */
    List<MemberRow> page(@Param("tenantId") UUID tenantId, @Param("offset") long offset, @Param("limit") int limit);

    /** 租户内给定标识的未删除、未离开的 Membership 及其 Identity 资料，按 Membership 标识排序；{@code ids} 不能为空。不受 Data Policy 过滤。 */
    List<MemberRow> findAll(@Param("tenantId") UUID tenantId, @Param("ids") Collection<UUID> ids);

    /** 租户内未删除、未离开的 Membership 数量。不受 Data Policy 过滤。 */
    long count(@Param("tenantId") UUID tenantId);

    /** Extended Attribute 值（JSON 对象文本）；不存在时返回 null。不受 Data Policy 过滤。 */
    String attributes(@Param("tenantId") UUID tenantId, @Param("membershipId") UUID membershipId);

    /** 整体替换 Extended Attribute 值，返回受影响行数。按 Token 租户限定，不受 Data Policy 过滤。 */
    int updateAttributes(
            @Param("tenantId") UUID tenantId,
            @Param("membershipId") UUID membershipId,
            @Param("attributes") String attributes,
            @Param("actor") UUID actor);

    /** 从租户全部 Membership 的 Extended Attribute 值中移除某个编码，返回被修改的 Membership。不受 Data Policy 过滤。 */
    List<UUID> removeAttribute(
            @Param("tenantId") UUID tenantId, @Param("code") String code, @Param("actor") UUID actor);
}
