package io.saasforge.iam.mfa.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import io.saasforge.iam.mfa.model.StoredAuthenticator;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

// Authenticator 属于全局 Identity，不按租户隔离（ADR-0019）；SDK 恢复的租户上下文不能改写这些 SQL。
@InterceptorIgnore(tenantLine = "true")
@Mapper
public interface IdentityAuthenticatorMapper {
    /** 身份未删除的 Authenticator，未绑定时为 null；不受 Data Policy 过滤。 */
    StoredAuthenticator findByIdentity(@Param("identityId") UUID identityId);

    /** 绑定 Authenticator；身份已有未删除的 Authenticator 时违反部分唯一索引。操作人为持有者本人，不受 Data Policy 过滤。 */
    void insert(
            @Param("id") UUID id,
            @Param("identityId") UUID identityId,
            @Param("secretCiphertext") byte[] secretCiphertext,
            @Param("lastUsedStep") long lastUsedStep,
            @Param("actor") UUID actor);

    /**
     * 仅当 {@code step} 晚于已记录的时间步时更新，返回受影响行数；为 0 表示该动态码已被使用（并发提交同一码时只有一个成功）。
     * 不受 Data Policy 过滤。
     */
    int advanceStep(@Param("id") UUID id, @Param("step") long step, @Param("actor") UUID actor);

    /** 逻辑删除身份的 Authenticator，返回受影响行数。不受 Data Policy 过滤。 */
    int deleteByIdentity(@Param("identityId") UUID identityId, @Param("actor") UUID actor);
}
