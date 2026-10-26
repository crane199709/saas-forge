package io.saasforge.iam.membership.mapper;

import io.saasforge.iam.membership.model.MembershipChange;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface MembershipMapper {
    /** 停用 Membership 并递增版本号，返回新版本；不存在时返回 null。管理操作按主键定位，不受 Data Policy 过滤。 */
    MembershipChange deactivate(@Param("membershipId") UUID membershipId);

    /** 递增版本号（如角色变更），返回新版本；不存在时返回 null。不受 Data Policy 过滤。 */
    MembershipChange incrementVersion(@Param("membershipId") UUID membershipId);
}
