package io.saasforge.sdk.core.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * 业务接口统一的响应信封，成功与失败共用同一结构，值为 null 的字段不输出（ADR-0015）。
 *
 * <p>Controller 用 {@link #ok(Object)}、{@link #created(Object)}、{@link #page(List, PageMeta)} 构造并显式返回；
 * {@code traceId} 由框架在写出响应前填入，HTTP 状态码按 {@code httpStatus} 输出。
 *
 * @param code 成功为 {@value #OK}；失败为点分错误码 {@code <领域>.<原因>}，同时是 i18n key
 * @param httpStatus 与实际 HTTP 状态码相同
 * @param data 业务数据，失败或无数据时为 null
 * @param errorMessage 面向开发者的英文说明，仅失败时出现；5xx 固定为 {@code Internal error}
 * @param userTip 面向最终用户、按请求语言生成的文案，仅失败时出现
 * @param traceId 当前请求的 W3C trace id
 * @param meta 附加元数据，目前只有分页信息
 * @param errors 逐字段校验明细，仅校验失败时出现
 * @param <T> 业务数据类型
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
        String code,
        int httpStatus,
        T data,
        String errorMessage,
        String userTip,
        String traceId,
        Meta meta,
        List<FieldError> errors) {

    /** 成功响应的 {@code code}。 */
    public static final String OK = "ok";

    /** 复制明细列表，避免调用方事后修改已构造的响应。 */
    public ApiResponse {
        errors = errors == null ? null : List.copyOf(errors);
    }

    /**
     * 200 成功响应。
     *
     * @param data 业务数据
     * @param <T> 业务数据类型
     * @return 成功信封
     */
    public static <T> ApiResponse<T> ok(T data) {
        return success(200, data, null);
    }

    /**
     * 不带数据的 200 成功响应，用于删除等无返回内容的操作；不使用 204，因为 204 不能携带 {@code traceId}。
     *
     * @return 成功信封
     */
    public static ApiResponse<Void> ok() {
        return success(200, null, null);
    }

    /**
     * 201 创建成功响应。
     *
     * @param data 新建的资源
     * @param <T> 业务数据类型
     * @return 成功信封
     */
    public static <T> ApiResponse<T> created(T data) {
        return success(201, data, null);
    }

    /**
     * 200 分页响应，{@code data} 为当前页数据，分页信息放在 {@code meta.page}。
     *
     * @param items 当前页数据
     * @param page 分页信息
     * @param <T> 元素类型
     * @return 成功信封
     */
    public static <T> ApiResponse<List<T>> page(List<T> items, PageMeta page) {
        return success(200, List.copyOf(items), new Meta(page));
    }

    /**
     * 失败响应。业务代码不直接调用，应抛 {@link ForgeException} 交由框架映射；供 SDK 的异常映射与安全处理器使用。
     *
     * @param httpStatus HTTP 状态码
     * @param code 错误码
     * @param errorMessage 面向开发者的说明
     * @param userTip 面向最终用户的文案
     * @param errors 逐字段明细，可为 null
     * @return 失败信封
     */
    public static ApiResponse<Void> error(
            int httpStatus, String code, String errorMessage, String userTip, List<FieldError> errors) {
        return new ApiResponse<>(code, httpStatus, null, errorMessage, userTip, null, null, errors);
    }

    /**
     * 返回填入 {@code traceId} 的副本。
     *
     * @param traceId 当前请求的 trace id
     * @return 新信封
     */
    public ApiResponse<T> withTraceId(String traceId) {
        return new ApiResponse<>(code, httpStatus, data, errorMessage, userTip, traceId, meta, errors);
    }

    private static <T> ApiResponse<T> success(int httpStatus, T data, Meta meta) {
        return new ApiResponse<>(OK, httpStatus, data, null, null, null, meta, null);
    }

    /**
     * 信封的附加元数据。
     *
     * @param page 分页信息
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Meta(PageMeta page) {}
}
