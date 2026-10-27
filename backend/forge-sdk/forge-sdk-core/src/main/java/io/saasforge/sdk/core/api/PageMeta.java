package io.saasforge.sdk.core.api;

/**
 * offset 分页的响应元数据，位于信封的 {@code meta.page}。
 *
 * @param number 当前页码，从 1 开始
 * @param size 每页条数
 * @param totalElements 总条数
 * @param totalPages 总页数，无数据时为 0
 */
public record PageMeta(int number, int size, long totalElements, int totalPages) {

    /**
     * 按总条数计算总页数。
     *
     * @param number 当前页码，从 1 开始
     * @param size 每页条数，必须大于 0
     * @param totalElements 总条数
     * @return 分页元数据
     */
    public static PageMeta of(int number, int size, long totalElements) {
        if (size <= 0) {
            throw new IllegalArgumentException("size must be positive");
        }
        return new PageMeta(number, size, totalElements, (int) ((totalElements + size - 1) / size));
    }
}
