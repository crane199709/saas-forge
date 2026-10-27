package io.saasforge.demo.order.model;

import io.saasforge.sdk.web.PageQuery;
import java.util.Locale;

/**
 * 进入 Mapper XML 的排序条件。
 *
 * @param column 列名，由 Controller 上 {@code @SortableFields} 白名单内的字段名按 camelCase 转 snake_case 得到
 * @param direction 方向，枚举值只能渲染为 ASC 或 DESC
 */
public record OrderSort(String column, PageQuery.Direction direction) {

    /**
     * 白名单只在 {@code @SortableFields} 声明一处：字段名与列名按命名约定一一对应，不再另维护字段到列的映射表，两处也就不会漂移。
     * 字段已由 SDK 按白名单校验，转换结果只取决于开发者声明的字段名，不含请求原文，可以进入 {@code ${}}。
     *
     * @param sort 已校验的排序条件
     * @return 排序条件
     */
    public static OrderSort from(PageQuery.Sort sort) {
        String column = sort.field().replaceAll("([A-Z])", "_$1").toLowerCase(Locale.ROOT);
        return new OrderSort(column, sort.direction());
    }
}
