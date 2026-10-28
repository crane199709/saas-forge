package io.saasforge.iam.organization.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import io.saasforge.iam.organization.model.DepartmentAssignmentRow;
import io.saasforge.iam.organization.model.PositionAssignmentRow;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** Membership 的部门与岗位分配。所有语句都按调用方传入的 Token 租户限定，不受 Data Policy 过滤。 */
// iam 的表不按租户行拦截隔离（ADR-0001），租户条件由 SQL 显式书写；消费事件时 SDK 恢复的租户上下文不能改写这些 SQL。
@InterceptorIgnore(tenantLine = "true")
@Mapper
public interface AssignmentMapper {
    /** 成员所在的未删除部门，主部门在前。 */
    List<DepartmentAssignmentRow> departmentsOf(
            @Param("tenantId") UUID tenantId, @Param("membershipIds") Collection<UUID> membershipIds);

    /** 成员担任的未删除岗位。 */
    List<PositionAssignmentRow> positionsOf(
            @Param("tenantId") UUID tenantId, @Param("membershipIds") Collection<UUID> membershipIds);

    /** 分配在以 {@code path} 为根的子树中任一部门的成员，去重。 */
    List<UUID> membershipsUnder(@Param("tenantId") UUID tenantId, @Param("path") String path);

    /** 分配在该部门的成员数（含兼任）。 */
    long countByDepartment(@Param("tenantId") UUID tenantId, @Param("departmentId") UUID departmentId);

    /** 担任该岗位的成员数。 */
    long countByPosition(@Param("tenantId") UUID tenantId, @Param("positionId") UUID positionId);

    /** 清除成员的全部部门分配。 */
    int clearDepartments(@Param("tenantId") UUID tenantId, @Param("membershipId") UUID membershipId);

    /** 新增一条部门分配。 */
    int insertDepartment(
            @Param("tenantId") UUID tenantId,
            @Param("membershipId") UUID membershipId,
            @Param("departmentId") UUID departmentId,
            @Param("primary") boolean primary);

    /** 清除成员的全部岗位分配。 */
    int clearPositions(@Param("tenantId") UUID tenantId, @Param("membershipId") UUID membershipId);

    /** 新增一条岗位分配。 */
    int insertPosition(
            @Param("tenantId") UUID tenantId,
            @Param("membershipId") UUID membershipId,
            @Param("positionId") UUID positionId);
}
