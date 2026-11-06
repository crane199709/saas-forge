package io.saasforge.iam.mfa.model;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 请求中 Recovery Code 的格式：提交时可以带分隔符与空格，校验前才规范化，因此只限长度与字符集；null 视为未提供。 */
@Size(min = 16, max = 64)
@Pattern(regexp = "[0-9A-Za-z\\s-]+")
@Constraint(validatedBy = {})
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface RecoveryCodeFormat {
    String message() default "invalid recovery code format";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
