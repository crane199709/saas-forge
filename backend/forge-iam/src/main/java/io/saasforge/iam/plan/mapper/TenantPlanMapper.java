package io.saasforge.iam.plan.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 租户 Plan 的副本。由 platform 广播的 TenantPlanChanged 写入，按参数中的租户定位，不受 Data Policy 过滤。 */
// iam 的表不按租户行拦截隔离（ADR-0001），租户条件由 SQL 显式书写；消费事件时 SDK 恢复的租户上下文不能改写这些 SQL。
@InterceptorIgnore(tenantLine = "true")
@Mapper
public interface TenantPlanMapper {
    /**
     * 应用 Plan 变更：未登记时登记，已登记且已应用的版本更旧时更新。
     *
     * @param memberQuota 成员数上限，null 表示不限
     * @return 应用了变更为 1；已应用同一或更新的版本时为 0
     */
    int apply(
            @Param("tenantId") UUID tenantId,
            @Param("planId") UUID planId,
            @Param("version") long version,
            @Param("memberQuota") Integer memberQuota);

    /** 删除租户 Feature Pack 中的全部权限码，随后整体写入新的。 */
    void clearPermissions(@Param("tenantId") UUID tenantId);

    void insertPermissions(@Param("tenantId") UUID tenantId, @Param("codes") Collection<String> codes);

    /** 租户是否已登记 Plan。 */
    boolean exists(@Param("tenantId") UUID tenantId);

    /** 租户 Feature Pack 中的权限码，按编码排序。 */
    List<String> permissions(@Param("tenantId") UUID tenantId);

    /**
     * 租户的成员数上限；未登记 Plan 或不限时为 null。
     */
    Integer memberQuota(@Param("tenantId") UUID tenantId);
}
