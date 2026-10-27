package io.saasforge.iam.identity.mapper;

import io.saasforge.iam.identity.model.ActiveMembership;
import io.saasforge.iam.identity.model.LoginCredential;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface IdentityMapper {
    /** 登录前尚无租户上下文；按账号或邮箱定位未删除的 active 身份的凭证，不要求已有 Membership，不受 Data Policy 过滤。 */
    LoginCredential findCredential(@Param("account") String account);

    /** 列出已认证身份可进入的 Membership（身份、Membership、租户均未删除且为 active），不受 Data Policy 过滤。 */
    List<ActiveMembership> findActiveMemberships(@Param("identityId") UUID identityId);

    /** 刷新时重新校验会话绑定的身份与 Membership 状态，不受 Data Policy 过滤。 */
    ActiveMembership findForRefresh(
            @Param("identityId") UUID identityId,
            @Param("membershipId") UUID membershipId,
            @Param("tenantId") UUID tenantId);

    /** 按邮箱（不区分大小写）或手机号定位未删除的 active 身份，供验证码登录使用，不受 Data Policy 过滤。 */
    String findActiveIdByContact(@Param("target") String target);

    /** 账号是否已被未删除的身份占用（不论状态），与唯一索引的范围一致，不受 Data Policy 过滤。 */
    boolean existsAccount(@Param("account") String account);

    /** 邮箱或手机号是否已被未删除的身份占用（不论状态），与唯一索引的范围一致，不受 Data Policy 过滤。 */
    boolean existsContact(@Param("target") String target);

    /** 注册新身份；注册时没有认证上下文，created_by、updated_by 为 NULL（系统）。 */
    void insert(
            @Param("id") UUID id,
            @Param("account") String account,
            @Param("email") String email,
            @Param("phone") String phone,
            @Param("displayName") String displayName,
            @Param("passwordHash") String passwordHash);
}
