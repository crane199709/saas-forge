package io.saasforge.iam.role.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import io.saasforge.iam.role.model.MembershipRoleRow;
import io.saasforge.iam.role.model.OwnerContact;
import io.saasforge.iam.role.model.RoleRow;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

// iam 的表不按租户行拦截隔离（ADR-0001），租户条件由 SQL 显式书写；消费事件时 SDK 恢复的租户上下文不能改写这些 SQL。
@InterceptorIgnore(tenantLine = "true")
@Mapper
public interface RoleMapper {
    /** 为租户插入一个预置角色，已存在同编码的未删除角色时不做修改。按租户定位，不受 Data Policy 过滤。 */
    int insertBuiltin(
            @Param("id") UUID id,
            @Param("tenantId") UUID tenantId,
            @Param("code") String code,
            @Param("name") String name);

    /**
     * 授予租户的 Owner 角色，已持有时不做修改。按租户定位，不受 Data Policy 过滤。
     *
     * @return 新授予为 1；已持有或租户的 Owner 已由其他 Membership 持有时为 0
     */
    int grantOwner(@Param("tenantId") UUID tenantId, @Param("membershipId") UUID membershipId);

    /** 撤销 Membership 持有的租户 Owner 角色，返回撤销行数；不是 Owner 时为 0。不受 Data Policy 过滤。 */
    int revokeOwner(@Param("tenantId") UUID tenantId, @Param("membershipId") UUID membershipId);

    /** 授予租户的 Tenant Admin 角色，已持有时不做修改。按租户定位，不受 Data Policy 过滤。 */
    int grantTenantAdmin(@Param("tenantId") UUID tenantId, @Param("membershipId") UUID membershipId);

    /** 租户当前的 Owner Membership；尚未授予时为 null。按 Token 租户限定，不受 Data Policy 过滤。 */
    UUID ownerOf(@Param("tenantId") UUID tenantId);

    /**
     * 租户 Owner 的联系方式，Owner 的 Membership 须为 active 或 invited（尚未接受邀请的初始管理员也要收到提醒）。
     * 按租户定位，不受 Data Policy 过滤。
     */
    List<OwnerContact> ownerContacts(@Param("tenantId") UUID tenantId);

    /** Membership 拥有的未删除角色编码，按编码排序。用于发布权限明细，不受 Data Policy 过滤。 */
    List<String> roleCodes(@Param("membershipId") UUID membershipId);

    /** 在事务内串行化同一租户的角色写操作，避免删除角色的同时把它分配给成员。按租户定位，不受 Data Policy 过滤。 */
    int lockTenant(@Param("tenantId") UUID tenantId);

    /** 租户的未删除角色及持有人数：Owner、Tenant Admin 在前，其余按编码排序。按 Token 租户限定，不受 Data Policy 过滤。 */
    List<RoleRow> list(@Param("tenantId") UUID tenantId);

    /** 租户内的未删除角色；不存在或属于其他租户时为 null。按 Token 租户限定，不受 Data Policy 过滤。 */
    RoleRow find(@Param("tenantId") UUID tenantId, @Param("id") UUID id);

    /**
     * 插入自定义角色；{@code actor} 为 null 表示系统（如开通时按模板预置）。按租户定位，不受 Data Policy 过滤。
     *
     * @return 新插入为 1；租户已有同编码的未删除角色时为 0
     */
    int insertCustom(
            @Param("tenantId") UUID tenantId,
            @Param("id") UUID id,
            @Param("code") String code,
            @Param("name") String name,
            @Param("actor") UUID actor);

    /** 自定义角色改名，返回受影响行数；预置角色不受影响。按 Token 租户限定，不受 Data Policy 过滤。 */
    int rename(
            @Param("tenantId") UUID tenantId,
            @Param("id") UUID id,
            @Param("name") String name,
            @Param("actor") UUID actor);

    /** 逻辑删除自定义角色，返回受影响行数；预置角色不受影响。按 Token 租户限定，不受 Data Policy 过滤。 */
    int delete(@Param("tenantId") UUID tenantId, @Param("id") UUID id, @Param("actor") UUID actor);

    /** 持有该角色的未删除 Membership。用于授权变更后递增版本，不受 Data Policy 过滤。 */
    List<UUID> holders(@Param("roleId") UUID roleId);

    /** 给定 Membership 持有的租户内未删除角色。按 Token 租户限定，不受 Data Policy 过滤。 */
    List<MembershipRoleRow> rolesOf(
            @Param("tenantId") UUID tenantId, @Param("membershipIds") Collection<UUID> membershipIds);

    /** 给定标识中属于该租户、未删除且不是 Owner 的角色数，用于校验分配。不受 Data Policy 过滤。 */
    long countAssignable(@Param("tenantId") UUID tenantId, @Param("ids") Collection<UUID> ids);

    /** 撤销 Membership 不在 {@code keep} 中的非 Owner 角色，返回撤销行数。Owner 只能经转让变更。不受 Data Policy 过滤。 */
    int revokeExcept(@Param("membershipId") UUID membershipId, @Param("keep") Collection<UUID> keep);

    /** 授予租户内给定的非 Owner 角色，已持有的不变，返回新增行数。按 Token 租户限定，不受 Data Policy 过滤。 */
    int grant(
            @Param("tenantId") UUID tenantId,
            @Param("membershipId") UUID membershipId,
            @Param("roleIds") Collection<UUID> roleIds);
}
