package io.saasforge.sdk.io;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 声明 record 的 Excel 列；字典列同时使用 DictionaryValue 声明字典。 */
@Target(ElementType.RECORD_COMPONENT)
@Retention(RetentionPolicy.RUNTIME)
public @interface ExcelColumn {
    /** 面向填写者的列名。 */
    String value();
    /** 列顺序，从小到大。 */
    int order();
    /** 是否必填；模板标记星号，导入时校验。 */
    boolean required() default false;
    /** 列宽，单位为字符。 */
    int width() default 24;
}
