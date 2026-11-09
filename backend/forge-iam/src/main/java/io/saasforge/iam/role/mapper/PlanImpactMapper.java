package io.saasforge.iam.role.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import io.saasforge.iam.role.model.MembershipCodeRow;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 预览 Plan 变更时统计受影响的 Membership。按参数中的租户限定，不受 Data Policy 过滤。 */
// iam 的表不按租户行拦截隔离（ADR-0001），租户条件由 SQL 显式书写；服务间调用没有 Token 中的租户。
@InterceptorIgnore(tenantLine = "true")
@Mapper
public interface PlanImpactMapper {
    /** 租户内 active 的 Membership 持有的角色编码；没有角色的 Membership 不返回。 */
    List<MembershipCodeRow> activeRoleCodes(@Param("tenantId") UUID tenantId);

    /** 租户内 active 的 Membership 经各角色获得的有效 Permission Code（已下线的不含），未按 Plan 裁剪。 */
    List<MembershipCodeRow> activeGrantedCodes(@Param("tenantId") UUID tenantId);
}
