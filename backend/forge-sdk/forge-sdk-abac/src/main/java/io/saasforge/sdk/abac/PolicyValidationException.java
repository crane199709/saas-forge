package io.saasforge.sdk.abac;

/** Data Policy 不合法（结构、属性、操作符或类型错误），在加载时抛出，避免运行时静默放行。 */
public class PolicyValidationException extends IllegalArgumentException {
    private final String path;

    /**
     * 创建异常。
     *
     * @param path 出错节点的 JSON Pointer，如 {@code /and/1/op}
     * @param message 错误说明
     */
    public PolicyValidationException(String path, String message) {
        super((path.isEmpty() ? "/" : path) + ": " + message);
        this.path = path;
    }

    /**
     * 出错节点的 JSON Pointer。
     *
     * @return 根节点为空串
     */
    public String path() {
        return path;
    }
}
