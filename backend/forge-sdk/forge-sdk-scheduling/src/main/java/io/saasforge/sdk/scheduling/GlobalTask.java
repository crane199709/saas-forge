package io.saasforge.sdk.scheduling;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 在 Spring Bean 的无参 void 方法上声明不绑定 Tenant 的 Global Task。
 *
 * <p>同一服务的 name 必须唯一。任务须幂等：Redis 锁提升执行效率，不能代替业务条件更新等正确性保证。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface GlobalTask {
    /** 服务内唯一的任务名，用作 Redis 锁键与指标标签。 */
    String name();

    /** 按 forge.business-zone 解释的六段 cron，与 fixedDelay 二选一。 */
    String cron() default "";

    /**
     * 触发间隔，支持属性占位符、Bean SpEL、ISO-8601 Duration、带单位的时长与毫秒值，与 cron 二选一。
     * 上一轮仍在执行时，本次触发跳过；间隔从上次触发结束计起，不等待异步执行体结束。
     */
    String fixedDelay() default "";
}
