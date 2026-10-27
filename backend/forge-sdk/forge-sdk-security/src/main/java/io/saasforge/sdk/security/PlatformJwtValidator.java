package io.saasforge.sdk.security;

import java.util.UUID;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/** 拒绝虽有合法签名但不是签发给 platform-console 的 Operator 凭证。 */
public final class PlatformJwtValidator implements OAuth2TokenValidator<Jwt> {
    /** 验证 Operator 标识与目标客户端，issuer 和时间由标准校验器负责。 */
    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        try {
            UUID.fromString(jwt.getSubject());
            if (jwt.getAudience().contains("platform-console") && jwt.getExpiresAt() != null) {
                return OAuth2TokenValidatorResult.success();
            }
        } catch (RuntimeException ignored) {
            // claim 的缺失或格式错误都作为无效凭证处理，不能变成业务接口的 500。
        }
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
    }
}
