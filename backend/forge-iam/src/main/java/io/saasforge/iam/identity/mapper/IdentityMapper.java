package io.saasforge.iam.identity.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import io.saasforge.iam.identity.model.ActiveMembership;
import io.saasforge.iam.identity.model.ContactIdentity;
import io.saasforge.iam.identity.model.LoginCredential;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

// iam 的表不按租户行拦截隔离（ADR-0001），租户条件由 SQL 显式书写；消费事件时 SDK 恢复的租户上下文不能改写这些 SQL。
@InterceptorIgnore(tenantLine = "true")
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

    /** 按邮箱（不区分大小写）或手机号定位未删除的身份（不论状态），供开通时邀请初始管理员，不受 Data Policy 过滤。 */
    ContactIdentity findByContact(@Param("target") String target);

    /** 为尚未注册的邀请对象建立待激活身份，没有账号与密码；由消费者写入，没有操作人。 */
    void insertPending(
            @Param("id") UUID id,
            @Param("email") String email,
            @Param("phone") String phone,
            @Param("displayName") String displayName);

    /** 接受邀请时为待激活身份设置凭证并激活，返回受影响行数；已激活或不存在时为 0。没有认证上下文，不受 Data Policy 过滤。 */
    int activatePending(
            @Param("id") UUID id,
            @Param("account") String account,
            @Param("displayName") String displayName,
            @Param("passwordHash") String passwordHash);

    /** 注册新身份；注册时没有认证上下文，created_by、updated_by 为 NULL（系统）。 */
    void insert(
            @Param("id") UUID id,
            @Param("account") String account,
            @Param("email") String email,
            @Param("phone") String phone,
            @Param("displayName") String displayName,
            @Param("passwordHash") String passwordHash);
}
