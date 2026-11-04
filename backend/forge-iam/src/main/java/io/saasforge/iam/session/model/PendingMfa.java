package io.saasforge.iam.session.model;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/**
 * 「待 MFA」状态：First Factor 已通过、尚未提交动态码或 Recovery Code，保存在 iam 登录会话中。
 *
 * <p>此时会话里没有已认证主体，授权端点不认可该会话，也就无法发起授权码流程。
 *
 * @param identityId 通过 First Factor 的身份
 * @param expiresAt 过期时刻，之后须重新提交 First Factor
 */
public record PendingMfa(UUID identityId, Instant expiresAt) implements Serializable {
    /** 登录会话中的属性名。 */
    public static final String SESSION_ATTRIBUTE = PendingMfa.class.getName();

    @Serial
    private static final long serialVersionUID = 1L;

    /** 在 {@code now} 时是否已过期。 */
    public boolean expiredAt(Instant now) {
        return !now.isBefore(expiresAt);
    }
}
