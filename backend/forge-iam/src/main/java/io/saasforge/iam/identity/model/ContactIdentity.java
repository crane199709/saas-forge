package io.saasforge.iam.identity.model;

import java.util.UUID;

/**
 * 按邮箱或手机号找到的身份。
 *
 * @param status {@code pending} 表示由邀请建立、尚未设置凭证
 */
public record ContactIdentity(UUID identityId, String status) {
    public boolean pending() {
        return IdentityStatus.PENDING.equals(status);
    }
}
