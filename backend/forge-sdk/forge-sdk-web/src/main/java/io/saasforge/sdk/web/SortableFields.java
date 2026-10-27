package io.saasforge.sdk.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明 {@link PageQuery} 参数允许的排序字段。未声明时不接受任何 {@code sort}；白名单让排序列名可以安全地进入 SQL。
 */
@Documented
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface SortableFields {

    /**
     * 允许的字段名，与请求中 {@code sort=字段,asc|desc} 的字段部分逐字匹配。
     *
     * @return 字段名
     */
    String[] value();
}
