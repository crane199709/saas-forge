package io.saasforge.iam.role.model;

/**
 * 角色在一个 Data Resource 上的 Data Policy：Data Scope 预设或自定义条件二选一。
 *
 * @param resource Data Resource 编码
 * @param scope Data Scope 预设 {@code self department department_and_below all}，或自定义条件 {@code custom}
 * @param condition {@code custom} 时为 JSON 表达式树，否则为 null
 */
public record DataPolicy(String resource, String scope, String condition) {
    /** 自定义条件的 {@code scope}。 */
    public static final String CUSTOM = "custom";

    /** 预设策略。 */
    public static DataPolicy preset(String resource, String scope) {
        return new DataPolicy(resource, scope, null);
    }
}
