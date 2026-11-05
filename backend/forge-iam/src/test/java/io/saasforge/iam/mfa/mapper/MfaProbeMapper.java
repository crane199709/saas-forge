package io.saasforge.iam.mfa.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 测试专用：读出 Authenticator 与 Recovery Code 的落库内容，验证只存密文与哈希。 */
@InterceptorIgnore(tenantLine = "true")
@Mapper
public interface MfaProbeMapper {
    /**
     * 身份未删除的 Authenticator 密钥密文，十六进制；未绑定时为 null。不受 Data Policy 过滤。
     *
     * @param identityId 身份
     * @return 密文
     */
    String activeSecretHex(@Param("identityId") UUID identityId);

    /**
     * 身份未删除的 Recovery Code 落库值。不受 Data Policy 过滤。
     *
     * @param identityId 身份
     * @return 落库值
     */
    List<String> activeRecoveryCodeHashes(@Param("identityId") UUID identityId);
}
