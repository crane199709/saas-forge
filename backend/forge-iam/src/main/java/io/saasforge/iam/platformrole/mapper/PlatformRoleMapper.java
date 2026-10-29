package io.saasforge.iam.platformrole.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import io.saasforge.iam.platformrole.model.OperatorRoleRow;
import io.saasforge.iam.platformrole.model.PlatformRoleRow;
import io.saasforge.iam.role.model.DataPolicy;
import io.saasforge.iam.role.model.RoleDataPolicyRow;
import io.saasforge.iam.role.model.RolePermissionRow;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

// iam 的表不按租户行拦截隔离（ADR-0001）；平台角色不属于租户，消费事件时 SDK 恢复的租户上下文不能改写这些 SQL。
@InterceptorIgnore(tenantLine = "true")
@Mapper
public interface PlatformRoleMapper {
    /** 在当前事务内串行化平台角色的授权、分配与 Operator 停用，事务结束时自动释放。平台全局数据，不受 Data Policy 过滤。 */
    int lock();

    /** 全部未删除的平台角色，超级管理员在前，其余按编码排序。平台全局数据，不受 Data Policy 过滤。 */
    List<PlatformRoleRow> list();

    /** 未删除的平台角色；不存在时为 null。平台全局数据，不受 Data Policy 过滤。 */
    PlatformRoleRow find(@Param("id") UUID id);

    /** 新建自定义平台角色；编码已被未删除的角色占用时不插入，返回 0。平台全局数据，不受 Data Policy 过滤。 */
    int insert(
            @Param("id") UUID id, @Param("code") String code, @Param("name") String name, @Param("actor") UUID actor);

    /** 自定义平台角色改名，返回受影响行数；超级管理员不受影响。平台全局数据，不受 Data Policy 过滤。 */
    int rename(@Param("id") UUID id, @Param("name") String name, @Param("actor") UUID actor);

    /** 逻辑删除自定义平台角色，返回受影响行数；超级管理员不受影响。平台全局数据，不受 Data Policy 过滤。 */
    int delete(@Param("id") UUID id, @Param("actor") UUID actor);

    /** Operator 持有的未删除平台角色编码，按编码排序。平台全局数据，不受 Data Policy 过滤。 */
    List<String> roleCodes(@Param("operatorId") UUID operatorId);

    /** 各平台角色授予的有效平台权限码（已下线的不出现）。平台全局数据，不受 Data Policy 过滤。 */
    List<RolePermissionRow> permissionsByRole();

    /** Operator 各平台角色授予的有效平台权限码的并集，按编码排序。平台全局数据，不受 Data Policy 过滤。 */
    List<String> grantedCodes(@Param("operatorId") UUID operatorId);

    /** 授予有效的平台权限码，忽略已授予的，返回新增行数。平台全局数据，不受 Data Policy 过滤。 */
    int grant(@Param("roleId") UUID roleId, @Param("codes") Collection<String> codes);

    /** 撤销给定权限码上的授权，返回删除行数。平台全局数据，不受 Data Policy 过滤。 */
    int revoke(@Param("roleId") UUID roleId, @Param("codes") Collection<String> codes);

    /** 删除角色的全部授权，随角色删除调用。平台全局数据，不受 Data Policy 过滤。 */
    int deletePermissions(@Param("roleId") UUID roleId);

    /** 各平台角色上的 Data Policy。平台全局数据，查询本身不受 Data Policy 过滤。 */
    List<RoleDataPolicyRow> policiesByRole();

    /** 角色上的 Data Policy，按资源编码排序。平台全局数据，查询本身不受 Data Policy 过滤。 */
    List<DataPolicy> policiesOfRole(@Param("roleId") UUID roleId);

    /** Operator 各平台角色上的 Data Policy，去重后按资源、范围、条件排序。平台全局数据，查询本身不受 Data Policy 过滤。 */
    List<DataPolicy> policiesOf(@Param("operatorId") UUID operatorId);

    /** 写入角色的 Data Policy。平台全局数据，查询本身不受 Data Policy 过滤。 */
    int insertPolicies(@Param("roleId") UUID roleId, @Param("policies") Collection<DataPolicy> policies);

    /** 删除角色在给定资源上的 Data Policy，其他资源（如已下线的）上的保留。平台全局数据，查询本身不受 Data Policy 过滤。 */
    int deletePolicies(@Param("roleId") UUID roleId, @Param("resources") Collection<String> resources);

    /** 删除角色的全部 Data Policy，随角色删除调用。平台全局数据，查询本身不受 Data Policy 过滤。 */
    int deleteAllPolicies(@Param("roleId") UUID roleId);

    /** 全部未删除 Operator 的平台角色分配。平台全局数据，不受 Data Policy 过滤。 */
    List<OperatorRoleRow> assignments();

    /** 持有该角色的未删除 Operator，按标识排序。平台全局数据，不受 Data Policy 过滤。 */
    List<UUID> holders(@Param("roleId") UUID roleId);

    /** 给定标识中未删除的平台角色数。平台全局数据，不受 Data Policy 过滤。 */
    long countRoles(@Param("ids") Collection<UUID> ids);

    /** 撤销 Operator 不在 {@code keep} 中的平台角色，返回删除行数。平台全局数据，不受 Data Policy 过滤。 */
    int revokeExcept(@Param("operatorId") UUID operatorId, @Param("keep") Collection<UUID> keep);

    /** 为 Operator 分配未删除的平台角色，忽略已分配的，返回新增行数。平台全局数据，不受 Data Policy 过滤。 */
    int assign(@Param("operatorId") UUID operatorId, @Param("roleIds") Collection<UUID> roleIds);

    /** 持有超级管理员、未删除且为 active 的 Operator 数。平台全局数据，不受 Data Policy 过滤。 */
    long countActiveSuperAdmins();
}
