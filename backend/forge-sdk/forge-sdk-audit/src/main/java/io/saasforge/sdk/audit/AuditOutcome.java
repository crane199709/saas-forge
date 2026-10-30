package io.saasforge.sdk.audit;

/** 被审计动作的结果。 */
public enum AuditOutcome {
    /** 动作完成。 */
    SUCCESS,
    /** 动作被拒绝或执行失败，事件带错误码。 */
    FAILURE
}
