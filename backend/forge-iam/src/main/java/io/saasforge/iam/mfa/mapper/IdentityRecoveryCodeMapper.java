package io.saasforge.iam.mfa.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import io.saasforge.iam.mfa.model.RecoveryCodeHash;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

// Recovery Code 属于全局 Identity，不按租户隔离（ADR-0019）；SDK 恢复的租户上下文不能改写这些 SQL。
@InterceptorIgnore(tenantLine = "true")
@Mapper
public interface IdentityRecoveryCodeMapper {
    /** 为身份写入一组 Recovery Code 哈希。操作人为持有者本人，不受 Data Policy 过滤。 */
    void insertAll(
            @Param("identityId") UUID identityId,
            @Param("codes") List<RecoveryCodeHash> codes,
            @Param("actor") UUID actor);

    /**
     * 作废身份的一个未使用的 Recovery Code，返回受影响行数；为 0 表示码不存在或已用过（并发提交同一码时只有一个成功）。
     * 登录流程中调用，操作人为 null。不受 Data Policy 过滤。
     */
    int consume(@Param("identityId") UUID identityId, @Param("codeHash") String codeHash, @Param("actor") UUID actor);

    /** 逻辑删除身份的全部 Recovery Code，返回受影响行数。不受 Data Policy 过滤。 */
    int deleteByIdentity(@Param("identityId") UUID identityId, @Param("actor") UUID actor);
}
