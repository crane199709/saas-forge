package io.saasforge.iam.organization.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import io.saasforge.iam.organization.model.PositionRow;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** Position。所有语句都按调用方传入的 Token 租户限定，不受 Data Policy 过滤。 */
// iam 的表不按租户行拦截隔离（ADR-0001），租户条件由 SQL 显式书写；消费事件时 SDK 恢复的租户上下文不能改写这些 SQL。
@InterceptorIgnore(tenantLine = "true")
@Mapper
public interface PositionMapper {
    /** 租户的全部未删除岗位，按名称排序。 */
    List<PositionRow> list(@Param("tenantId") UUID tenantId);

    /** 给定标识中属于该租户且未删除的岗位数，用于校验分配。 */
    long countExisting(@Param("tenantId") UUID tenantId, @Param("ids") Collection<UUID> ids);

    /** 插入岗位。 */
    int insert(
            @Param("tenantId") UUID tenantId,
            @Param("id") UUID id,
            @Param("name") String name,
            @Param("actor") UUID actor);

    /** 改名，返回受影响行数。 */
    int rename(
            @Param("tenantId") UUID tenantId,
            @Param("id") UUID id,
            @Param("name") String name,
            @Param("actor") UUID actor);

    /** 逻辑删除，返回受影响行数。 */
    int delete(@Param("tenantId") UUID tenantId, @Param("id") UUID id, @Param("actor") UUID actor);
}
