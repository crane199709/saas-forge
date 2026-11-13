package io.saasforge.sdk.io;

/** 回滚不可用与逐记录冲突的稳定原因码，不含业务字段原文。 */
public enum ImportRollbackReason {
    /** 导入任务未成功完成。 */
    TASK_NOT_SUCCEEDED,
    /** 业务服务不支持该任务的导入模型。 */
    MODEL_MISMATCH,
    /** 追加导入或未覆盖已有记录。 */
    NO_SNAPSHOTS,
    /** 覆盖前快照已超过实际保留截止时间。 */
    SNAPSHOT_EXPIRED,
    /** 任务登记的快照已缺失。 */
    SNAPSHOT_UNAVAILABLE,
    /** 业务记录不存在、已删除或当前成员不可见，不区分具体原因。 */
    RECORD_UNAVAILABLE,
    /** 无法核对导入完成后的修改标记。 */
    VERSION_UNAVAILABLE,
    /** 记录在本次导入后又发生修改。 */
    RECORD_MODIFIED,
    /** 同一导入模型的同一记录被另一任务实际覆盖。 */
    LATER_IMPORT
}
