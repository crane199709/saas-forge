package io.saasforge.sdk.scheduling;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 在 Spring Bean 的无参 void 方法上声明逐 Tenant 执行的任务。
 *
 * <p>每轮对试用或正常 Tenant 顺序执行，单个 Tenant 失败不影响后续 Tenant；任务必须幂等，失败不在本轮重试。
 * 执行时只绑定 Tenant，不绑定 Membership 或操作人，审计列操作人为 NULL。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface TenantTask {
    /** 服务内唯一的任务名，与 GlobalTask 共用名称空间，用作锁键与指标标签。 */
    String name();

    /** 按 forge.business-zone 解释的六段 cron，与 fixedDelay 二选一。 */
    String cron() default "";

    /** 触发间隔，支持占位符、Bean SpEL、ISO Duration、带单位时长与毫秒值，与 cron 二选一。 */
    String fixedDelay() default "";
}
