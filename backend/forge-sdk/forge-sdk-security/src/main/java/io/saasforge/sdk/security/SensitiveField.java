package io.saasforge.sdk.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明 DTO 字段的 Sensitive Field 登记，供 Resource Manifest 收集与统一脱敏使用。
 *
 * <p>路径在服务内唯一；同一 record component 传播到字段与 accessor 的注解只登记一次。
 * 改动声明须升级 {@code forge.manifest.version}，未声明的字段不自动脱敏。
 */
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface SensitiveField {
    /**
     * 服务内唯一的点分字段路径，各段遵循 Manifest 的普通标识符规则。
     *
     * @return 如 {@code customer.phone}
     */
    String path();

    /**
     * 数据分级，决定默认规则与导出限制，不参与权限判断。
     *
     * @return {@code L1 L2 L3 L4} 之一；非法值在生成与上报时拒绝
     */
    String grade();

    /**
     * 默认 Masking Rule 的编码，不是表达式或正则。
     *
     * @return 规则编码；空串表示由平台按分级选择默认规则
     */
    String defaultRule() default "";
}
