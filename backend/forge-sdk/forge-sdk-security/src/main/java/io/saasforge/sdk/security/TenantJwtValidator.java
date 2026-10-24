package io.saasforge.sdk.security;

import java.util.UUID;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/** 拒绝虽有合法签名但缺少租户身份边界的凭证。 */
public final class TenantJwtValidator implements OAuth2TokenValidator<Jwt> {
    /** 验证上下文 claim 与目标客户端，issuer 和时间由标准校验器负责。 */
    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        try {
            UUID.fromString(jwt.getSubject());
            UUID.fromString(jwt.getClaimAsString("tenant_id"));
            UUID.fromString(jwt.getClaimAsString("membership_id"));
            Object version = jwt.getClaim("ver");
            if ((version instanceof Long || version instanceof Integer)
                    && ((Number) version).longValue() > 0
                    && jwt.getAudience().contains("tenant-app")
                    && jwt.getExpiresAt() != null) {
                return OAuth2TokenValidatorResult.success();
            }
        } catch (RuntimeException ignored) {
            // claim 的缺失或格式错误都作为无效凭证处理，不能变成业务接口的 500。
        }
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
    }
}
