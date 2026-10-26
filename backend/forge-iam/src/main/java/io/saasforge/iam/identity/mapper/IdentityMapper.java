package io.saasforge.iam.identity.mapper;

import io.saasforge.iam.identity.model.ActiveMembership;
import io.saasforge.iam.identity.model.LoginCredential;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface IdentityMapper {
    /** 登录前尚无租户上下文；按账号或邮箱定位 active 身份的凭证，不要求已有 Membership，不受 Data Policy 过滤。 */
    LoginCredential findCredential(@Param("account") String account);

    /** 列出已认证身份可进入的 Membership（身份、Membership、租户均为 active），不受 Data Policy 过滤。 */
    List<ActiveMembership> findActiveMemberships(@Param("identityId") UUID identityId);

    /** 刷新时重新校验会话绑定的身份与 Membership 状态，不受 Data Policy 过滤。 */
    ActiveMembership findForRefresh(
            @Param("identityId") UUID identityId,
            @Param("membershipId") UUID membershipId,
            @Param("tenantId") UUID tenantId);
}
