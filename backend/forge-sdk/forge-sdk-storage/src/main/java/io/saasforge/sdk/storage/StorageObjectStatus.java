package io.saasforge.sdk.storage;

/** 文件登记的生命周期状态；HELD 为后续合规冻结预留，当前版本不会自动进入该状态。 */
public enum StorageObjectStatus {
    /** 已签发上传凭证，尚未确认。 */
    PENDING,
    /** 已确认且可读取。 */
    STORED,
    /** 已失效，宽限期内可恢复。 */
    EXPIRED,
    /** 宽限期已结束，等待安全删除存储对象。 */
    PURGE_PENDING,
    /** 存储对象已删除，完成后移除登记。 */
    DELETED,
    /** 预留的合规冻结状态。 */
    HELD
}
