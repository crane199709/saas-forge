package io.saasforge.iam.mfa.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import io.saasforge.iam.mfa.model.StoredAuthenticator;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

// Operator 属于 Platform Realm，表上没有租户列；SDK 恢复的租户上下文不能改写这些 SQL。
@InterceptorIgnore(tenantLine = "true")
@Mapper
public interface OperatorAuthenticatorMapper {
    /** Operator 未删除的 Authenticator，未绑定时为 null；不受 Data Policy 过滤。 */
    StoredAuthenticator findByOperator(@Param("operatorId") UUID operatorId);

    /** 绑定 Authenticator；Operator 已有未删除的 Authenticator 时违反部分唯一索引。不受 Data Policy 过滤。 */
    void insert(
            @Param("id") UUID id,
            @Param("operatorId") UUID operatorId,
            @Param("secretCiphertext") byte[] secretCiphertext,
            @Param("lastUsedStep") long lastUsedStep,
            @Param("actor") UUID actor);

    /**
     * 仅当 {@code step} 晚于已记录的时间步时更新，返回受影响行数；为 0 表示该动态码已被使用（并发提交同一码时只有一个成功）。
     * 不受 Data Policy 过滤。
     */
    int advanceStep(@Param("id") UUID id, @Param("step") long step, @Param("actor") UUID actor);

    /** 逻辑删除 Operator 的 Authenticator，返回受影响行数。不受 Data Policy 过滤。 */
    int deleteByOperator(@Param("operatorId") UUID operatorId, @Param("actor") UUID actor);
}
