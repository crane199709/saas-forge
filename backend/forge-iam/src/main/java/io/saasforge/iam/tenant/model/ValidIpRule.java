package io.saasforge.iam.tenant.model;

import io.saasforge.sdk.core.TenantIpAllowlist;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;

/** 保留容器元素的属性路径，使前端可定位到 rules[index]。 */
@Target(ElementType.TYPE_USE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ValidIpRule.Validator.class)
public @interface ValidIpRule {
    String message() default "Invalid IPv4, IPv6 or CIDR rule";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    final class Validator implements ConstraintValidator<ValidIpRule, String> {
        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            if (value == null) return false;
            try {
                TenantIpAllowlist.parse(List.of(value));
                return true;
            } catch (IllegalArgumentException error) {
                return false;
            }
        }
    }
}
