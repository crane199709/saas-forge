package io.saasforge.sdk.io;

/** 业务回滚扩展点，所有读取与恢复均须遵守当前成员的数据权限。 */
public interface ImportRollbackWriter extends ImportVersionReader {
    /** 在 SDK 当前逐记录事务中锁定可见业务行并返回最新修改标记；不存在或不可见返回 null。 */
    String lockAndReadVersion(String recordId);

    /** 仅恢复快照内的字段；SDK 保持业务行锁，恢复失败须抛异常使业务写入与成功标记共同回滚。 */
    void restore(ImportBackup backup);
}
