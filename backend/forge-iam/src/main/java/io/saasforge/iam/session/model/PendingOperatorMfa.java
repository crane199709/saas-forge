package io.saasforge.iam.session.model;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/**
 * Operator 密码已通过、尚未完成第二步的登录状态，保存在 iam 登录会话中；此时会话里没有已认证主体，授权端点不认可该会话。
 *
 * @param operatorId 通过密码的 Operator
 * @param expiresAt 过期时刻，之后须重新输入密码
 * @param bindRequired true：要求 Operator 使用 MFA 而尚未绑定，须当场绑定（「必须绑定」）；false：已绑定，须提交动态码或 Recovery
 *     Code（「待 MFA」）
 */
public record PendingOperatorMfa(UUID operatorId, Instant expiresAt, boolean bindRequired) implements Serializable {
    /** 登录会话中的属性名。 */
    public static final String SESSION_ATTRIBUTE = PendingOperatorMfa.class.getName();

    @Serial
    private static final long serialVersionUID = 1L;

    /** 在 {@code now} 时是否已过期。 */
    public boolean expiredAt(Instant now) {
        return !now.isBefore(expiresAt);
    }
}
