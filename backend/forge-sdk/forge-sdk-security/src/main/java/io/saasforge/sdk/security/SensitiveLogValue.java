package io.saasforge.sdk.security;

/** 显式标记日志中的 Sensitive Field；即使未启用 converter，字符串化也不会泄露原值。 */
public final class SensitiveLogValue {
    private final String path;
    private final Object value;

    private SensitiveLogValue(String path, Object value) {
        this.path = path;
        this.value = value;
    }

    /** 创建日志参数；path 使用 Manifest 中的完整字段路径，不接受原文放入 path。 */
    public static SensitiveLogValue of(String path, Object value) {
        return new SensitiveLogValue(path, value);
    }

    String path() {
        return path;
    }

    Object value() {
        return value;
    }

    /** 始终返回固定占位，只有统一日志 converter 能读取原值。 */
    @Override
    public String toString() {
        return "[redacted]";
    }
}
