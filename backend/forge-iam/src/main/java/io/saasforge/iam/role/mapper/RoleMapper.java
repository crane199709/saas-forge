package io.saasforge.iam.role.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import io.saasforge.iam.role.model.OwnerContact;
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

    /**
     * 租户 Owner 的联系方式，Owner 的 Membership 须为 active 或 invited（尚未接受邀请的初始管理员也要收到提醒）。
     * 按租户定位，不受 Data Policy 过滤。
     */
    List<OwnerContact> ownerContacts(@Param("tenantId") UUID tenantId);

    /** Membership 拥有的未删除角色编码，按编码排序。用于发布权限明细，不受 Data Policy 过滤。 */
    List<String> roleCodes(@Param("membershipId") UUID membershipId);
}
