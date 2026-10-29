package io.saasforge.platform.tenant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.saasforge.platform.tenant.entity.Tenant;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface TenantMapper extends BaseMapper<Tenant> {
    /** 一页未删除的租户，按标识（UUIDv7）倒序即最近创建的在前。平台全局表，不受 Data Policy 过滤。 */
    List<Tenant> page(@Param("offset") long offset, @Param("limit") int limit);

    /**
     * 为租户分配 Plan 并递增 Plan 版本，返回新版本；租户不存在时为 null。平台全局表，不受 Data Policy 过滤。
     *
     * @param actor 操作的 Operator，系统发起时为 null
     */
    Long assignPlan(@Param("tenantId") UUID tenantId, @Param("planId") UUID planId, @Param("actor") UUID actor);

    /**
     * 递增使用该 Plan 的全部租户的 Plan 版本，返回这些租户的标识与新版本（只填充 {@code id}、{@code planVersion}）。
     * 平台全局表，不受 Data Policy 过滤。
     */
    List<Tenant> touchPlan(@Param("planId") UUID planId, @Param("actor") UUID actor);
}
