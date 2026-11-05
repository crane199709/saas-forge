package io.saasforge.sdk.referencedata;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** {@link DictionaryValue} 的校验器，由 Spring 的约束校验器工厂按构造器注入 {@link ReferenceData}。 */
public final class DictionaryValueValidator implements ConstraintValidator<DictionaryValue, String> {
    private final ReferenceData referenceData;
    private String dictionary;

    /**
     * 创建校验器。
     *
     * @param referenceData 当前租户的参考数据
     */
    public DictionaryValueValidator(ReferenceData referenceData) {
        this.referenceData = referenceData;
    }

    @Override
    public void initialize(DictionaryValue annotation) {
        this.dictionary = annotation.value();
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value == null || referenceData.isSelectable(dictionary, value);
    }
}
