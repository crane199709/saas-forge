package io.saasforge.sdk.audit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明一个业务操作需要审计：方法正常返回记为成功，抛出异常记为失败并带错误码，异常照常抛给调用方。
 *
 * <p>操作人取自当前认证（Token 的 {@code sub}），租户取自当前租户上下文：有租户上下文的是 Tenant Realm 事件，
 * 否则是 Platform Realm 事件。审计在方法安全与事务之外执行，因此权限不足（{@code auth.forbidden}）与事务提交失败也会记为失败。
 *
 * <p>标注在 Spring Bean 的 public 方法上，经代理调用时生效；同一个类内部的自调用不经代理，不会记录。
 *
 * <pre>{@code
 * @AuditLog(action = "order.delete", resourceType = "order", resourceId = "#id")
 * ApiResponse<Void> delete(@PathVariable UUID id) { ... }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AuditLog {
    /**
     * 动作，点分、各段 lowerCamelCase，建议 {@code <资源>.<动词>}，如 {@code order.create}。
     *
     * @return 动作
     */
    String action();

    /**
     * 操作对象类型，如 {@code order}。
     *
     * @return 对象类型，默认没有
     */
    String resourceType() default "";

    /**
     * 操作对象标识的 SpEL 表达式：可按参数名（{@code #id}）或位置（{@code #p0}）引用参数，以 {@code #result} 引用返回值，
     * 如 {@code #result.data().id()}。失败时 {@code #result} 为 null；表达式出错或结果为 null 时不记录对象标识。
     *
     * @return 表达式，默认不记录对象标识
     */
    String resourceId() default "";
}
