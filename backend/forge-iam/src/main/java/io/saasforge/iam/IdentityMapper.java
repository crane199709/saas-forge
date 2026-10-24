package io.saasforge.iam;

import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface IdentityMapper {
    /** 登录前尚无租户上下文；只查询凭证匹配身份的有效 Membership，不受 Data Policy 过滤。 */
    List<LoginIdentity> findForLogin(@Param("account") String account);
    /** 标准授权流程通过全局身份读取唯一有效 Membership，不受 Data Policy 过滤。 */
    List<LoginIdentity> findForIdentity(@Param("identityId") UUID identityId);

    /** 刷新时重新校验会话绑定的身份与 Membership 状态，不受 Data Policy 过滤。 */
    LoginIdentity findForRefresh(
            @Param("identityId") UUID identityId,
            @Param("membershipId") UUID membershipId,
            @Param("tenantId") UUID tenantId);
}
