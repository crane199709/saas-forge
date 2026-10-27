package io.saasforge.sdk.web;

import io.saasforge.sdk.core.api.PageMeta;
import java.util.List;

/**
 * 已校验的 offset 分页与排序参数。Controller 方法声明本类型参数即可从 query 的 {@code page}、{@code size}、{@code sort}
 * 解析，可排序字段用 {@link SortableFields} 声明；参数非法时返回 400 {@code request.invalid}。
 *
 * @param page 页码，从 1 开始
 * @param size 每页条数，1 到 {@value #MAX_SIZE}
 * @param sorts 排序条件，按请求中的顺序；字段已在白名单内
 */
public record PageQuery(int page, int size, List<Sort> sorts) {

    /** 未传 {@code size} 时的每页条数。 */
    public static final int DEFAULT_SIZE = 20;

    /** {@code size} 上限，超出返回 400 而不是静默截断。 */
    public static final int MAX_SIZE = 100;

    /** 复制排序条件，避免调用方修改。 */
    public PageQuery {
        sorts = List.copyOf(sorts);
    }

    /**
     * 当前页第一条记录的偏移量，供 Mapper XML 的 {@code OFFSET} 使用。
     *
     * @return 偏移量
     */
    public long offset() {
        return (long) (page - 1) * size;
    }

    /**
     * 按总条数生成响应的分页元数据。
     *
     * @param totalElements 总条数
     * @return 分页元数据
     */
    public PageMeta toPageMeta(long totalElements) {
        return PageMeta.of(page, size, totalElements);
    }

    /**
     * 一个排序条件。字段名是接口声明的白名单中的值，进入 SQL 前由服务映射为列名。
     *
     * @param field 字段名
     * @param direction 方向
     */
    public record Sort(String field, Direction direction) {}

    /** 排序方向。 */
    public enum Direction {
        /** 升序。 */
        ASC,
        /** 降序。 */
        DESC
    }
}
