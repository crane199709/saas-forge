package io.saasforge.sdk.io;

/** 覆盖导入的业务扩展；SDK 在同一事务中依次读取旧值、持久化快照、写入并保存写后修改标记。 */
public interface ImportWriter<T> {
    /**
     * 锁定已有记录并返回本次会覆盖的字段旧值；新增行返回 null。
     * 锁须持有到确认事务结束，防止读取旧值与覆盖写入之间发生并发修改。
     */
    ImportSnapshot beforeWrite(T row);

    /**
     * 写入当前行；已有记录返回写入后从数据库读取的修改标记（updated_at 或版本列）。
     * 新增行的返回值不保存。修改标记必须在记录每次更新时变化，供后续冲突判定使用。
     */
    String write(T row);
}
