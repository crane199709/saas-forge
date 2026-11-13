package io.saasforge.sdk.io;

/** 以当前租户和当前数据权限读取业务记录的修改标记。 */
@FunctionalInterface
public interface ImportVersionReader {
    /** 返回数据库当前 updated_at 或版本列文本；不存在、已删除或当前成员不可见时返回 null。 */
    String readVersion(String recordId);
}
