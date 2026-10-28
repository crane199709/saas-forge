package io.saasforge.iam.role.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import io.saasforge.iam.role.model.DataPolicy;
import io.saasforge.iam.role.model.RoleDataPolicyRow;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 自定义角色上的 Data Policy。角色须由调用方先按 Token 租户确认归属；本表是策略的来源，自身不受 Data Policy 过滤。 */
// iam 的表不按租户行拦截隔离（ADR-0001），租户条件由 SQL 显式书写；消费事件时 SDK 恢复的租户上下文不能改写这些 SQL。
@InterceptorIgnore(tenantLine = "true")
@Mapper
public interface RoleDataPolicyMapper {
    /** 租户各未删除角色上的策略，按资源编码排序。 */
    List<RoleDataPolicyRow> listByTenant(@Param("tenantId") UUID tenantId);

    /** 角色上的策略，按资源编码排序。 */
    List<DataPolicy> byRole(@Param("roleId") UUID roleId);

    /** Membership 经未删除角色获得的策略，按资源编码、范围排序；同一资源上多个角色的策略各占一行。 */
    List<DataPolicy> policiesOf(@Param("membershipId") UUID membershipId);

    /** 写入角色上的策略，返回新增行数；调用方先删除旧策略。 */
    int insert(@Param("roleId") UUID roleId, @Param("policies") Collection<DataPolicy> policies);

    /** 删除角色在给定资源上的策略，整体替换时调用；当前清单未声明的资源上的策略保留，资源恢复后随之恢复。 */
    int deleteResources(@Param("roleId") UUID roleId, @Param("resources") Collection<String> resources);

    /** 删除角色上的全部策略，删除角色时调用。 */
    int deleteByRole(@Param("roleId") UUID roleId);
}
