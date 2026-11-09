package io.saasforge.iam.role.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.Collection;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 预览 Plan 变更时统计受影响的 Membership。按参数中的租户限定，不受 Data Policy 过滤。 */
// iam 的表不按租户行拦截隔离（ADR-0001），租户条件由 SQL 显式书写；服务间调用没有 Token 中的租户。
@InterceptorIgnore(tenantLine = "true")
@Mapper
public interface PlanImpactMapper {
    /** 租户内 active 的 Membership 数量。 */
    long countActive(@Param("tenantId") UUID tenantId);

    /**
     * 租户内持有给定 Permission Code 的 active Membership 数量。
     *
     * @param codes 不能为空
     * @param allPermissionRoles 拥有 Plan 范围内全部权限的预置角色编码，不落授权记录，持有即视为持有 {@code codes}；不能为空
     */
    long countHolders(
            @Param("tenantId") UUID tenantId,
            @Param("codes") Collection<String> codes,
            @Param("allPermissionRoles") Collection<String> allPermissionRoles);
}
