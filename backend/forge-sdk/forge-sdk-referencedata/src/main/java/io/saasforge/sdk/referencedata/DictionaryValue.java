package io.saasforge.sdk.referencedata;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 字符串取值须为当前租户该字典的可选项：停用、下线与不存在的编码都不通过。null 视为未填写，由 {@code @NotNull} 等约束决定。
 *
 * <p>校验器经 Spring 的约束校验器工厂注入 {@link ReferenceData}，因此只在 Spring 管理的 Validator（如控制器上的 {@code @Valid}）中生效；
 * 校验时须已绑定租户上下文。错误响应中字段的错误码为 {@code validation.dictionaryValue}。
 */
@Documented
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = DictionaryValueValidator.class)
public @interface DictionaryValue {
    /**
     * 字典编码，写作 {@code 服务:编码} 引用其他服务的字典，不带前缀即本服务的字典。
     *
     * @return 字典编码
     */
    String value();

    /**
     * 未在服务消息中配置 {@code validation.dictionaryValue} 时的默认说明。
     *
     * @return 校验失败的说明
     */
    String message() default "请选择有效的选项";

    /**
     * 校验分组。
     *
     * @return 分组
     */
    Class<?>[] groups() default {};

    /**
     * 约束负载。
     *
     * @return 负载
     */
    Class<? extends Payload>[] payload() default {};
}
