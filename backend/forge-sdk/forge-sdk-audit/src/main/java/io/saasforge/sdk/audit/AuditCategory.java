package io.saasforge.sdk.audit;

/** Audit Event 的三类来源。 */
public enum AuditCategory {
    /** 登录成功、失败、退出与切换租户，由 iam 产出。 */
    LOGIN,
    /** Role、授权、Data Policy 与 Membership 变更，由 iam 产出。 */
    PERMISSION,
    /** 开发者以 {@link AuditLog} 声明的业务操作。 */
    OPERATION
}
