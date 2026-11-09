package io.saasforge.iam.organization.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import io.saasforge.iam.organization.model.DepartmentRow;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** Department 树。所有语句都按调用方传入的 Token 租户限定，不受 Data Policy 过滤。 */
// iam 的表不按租户行拦截隔离（ADR-0001），租户条件由 SQL 显式书写；消费事件时 SDK 恢复的租户上下文不能改写这些 SQL。
@InterceptorIgnore(tenantLine = "true")
@Mapper
public interface DepartmentMapper {
    /** 取得租户的组织数据事务级咨询锁：部门树、岗位、扩展属性与成员分配的写操作在同一租户内串行。 */
    int lockTenant(@Param("tenantId") UUID tenantId);

    /** 租户的全部未删除部门，按路径排序，上级总在下级之前。 */
    List<DepartmentRow> list(@Param("tenantId") UUID tenantId);

    /** 按主键查找未删除的部门；不属于该租户时返回 null。 */
    DepartmentRow find(@Param("tenantId") UUID tenantId, @Param("id") UUID id);

    /** 当前租户的已删除部门，按最后更新时间倒序。 */
    List<DepartmentRow> listDeleted(@Param("tenantId") UUID tenantId);

    /** 当前租户内已删除的部门，其他情况返回 null。 */
    DepartmentRow findDeleted(@Param("tenantId") UUID tenantId, @Param("id") UUID id);

    /** 按当前父部门路径恢复，维护审计列，只影响当前租户已删除行。 */
    int restore(
            @Param("tenantId") UUID tenantId,
            @Param("id") UUID id,
            @Param("path") String path,
            @Param("actor") UUID actor);

    /** 给定标识中属于该租户且未删除的部门数，用于校验分配。 */
    long countExisting(@Param("tenantId") UUID tenantId, @Param("ids") Collection<UUID> ids);

    /** 插入部门，路径由调用方按上级路径计算。 */
    int insert(
            @Param("tenantId") UUID tenantId,
            @Param("id") UUID id,
            @Param("parentId") UUID parentId,
            @Param("name") String name,
            @Param("path") String path,
            @Param("actor") UUID actor);

    /** 改名，返回受影响行数。 */
    int rename(
            @Param("tenantId") UUID tenantId,
            @Param("id") UUID id,
            @Param("name") String name,
            @Param("actor") UUID actor);

    /** 把以 {@code oldPath} 为根的未删除子树整体改到 {@code newPath} 之下，并改写子树根的上级。 */
    int move(
            @Param("tenantId") UUID tenantId,
            @Param("id") UUID id,
            @Param("parentId") UUID parentId,
            @Param("oldPath") String oldPath,
            @Param("newPath") String newPath,
            @Param("actor") UUID actor);

    /** 未删除的直接下级数量。 */
    long countChildren(@Param("tenantId") UUID tenantId, @Param("id") UUID id);

    /** 逻辑删除。 */
    int delete(@Param("tenantId") UUID tenantId, @Param("id") UUID id, @Param("actor") UUID actor);
}
