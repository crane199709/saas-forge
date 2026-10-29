package io.saasforge.iam.role.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import io.saasforge.iam.role.model.RolePermissionRow;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 自定义角色的 Permission Code 授权。角色须由调用方先按 Token 租户确认归属，不受 Data Policy 过滤。 */
// iam 的表不按租户行拦截隔离（ADR-0001），租户条件由 SQL 显式书写；消费事件时 SDK 恢复的租户上下文不能改写这些 SQL。
@InterceptorIgnore(tenantLine = "true")
@Mapper
public interface RolePermissionMapper {
    /** 租户各未删除角色授予的有效权限码，按编码排序；已下线的授权不返回，超出 Plan 的由调用方过滤。 */
    List<RolePermissionRow> listByTenant(@Param("tenantId") UUID tenantId);

    /** Membership 经未删除角色获得的有效权限码，多个角色取并集，按编码排序；超出 Plan 的由调用方过滤。 */
    List<String> grantedCodes(@Param("membershipId") UUID membershipId);

    /** 授予给定编码中 active 的权限码，已授予的不变，返回新增行数。 */
    int grant(@Param("roleId") UUID roleId, @Param("codes") Collection<String> codes);

    /** 撤销给定编码的授权，返回撤销行数；{@code codes} 不能为空。 */
    int revoke(@Param("roleId") UUID roleId, @Param("codes") Collection<String> codes);

    /** 删除角色的全部授权（含已下线的），删除角色时调用。 */
    int deleteByRole(@Param("roleId") UUID roleId);
}
