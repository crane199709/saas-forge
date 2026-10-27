package io.saasforge.sdk.core.api;

import java.util.Arrays;

/**
 * 业务异常，由框架映射为失败信封：状态码与 {@code code} 取自 {@link ErrorCode}，{@code userTip} 按 code 查文案并以
 * {@code args} 填充占位符。业务错误统一抛本异常，不自建异常类型。
 */
public class ForgeException extends RuntimeException {

    private final transient ErrorCode errorCode;
    private final transient Object[] args;

    /**
     * 以 code 与参数生成 {@code errorMessage}。
     *
     * @param errorCode 错误码
     * @param args 文案占位符参数
     */
    public ForgeException(ErrorCode errorCode, Object... args) {
        this(errorCode, defaultMessage(errorCode, args), args);
    }

    private ForgeException(ErrorCode errorCode, String errorMessage, Object[] args) {
        super(errorMessage);
        this.errorCode = errorCode;
        this.args = args.clone();
    }

    /**
     * 显式提供面向开发者的 {@code errorMessage}，用于需要带 id 等排障上下文的场景。
     *
     * @param errorCode 错误码
     * @param errorMessage 英文说明，不做国际化
     * @param args 文案占位符参数
     * @return 业务异常
     */
    public static ForgeException withErrorMessage(ErrorCode errorCode, String errorMessage, Object... args) {
        return new ForgeException(errorCode, errorMessage, args);
    }

    /**
     * 错误码。
     *
     * @return 错误码
     */
    public ErrorCode errorCode() {
        return errorCode;
    }

    /**
     * 文案占位符参数。
     *
     * @return 参数副本
     */
    public Object[] args() {
        return args.clone();
    }

    /**
     * 面向开发者的说明，即 {@link #getMessage()}。
     *
     * @return 英文说明
     */
    public String errorMessage() {
        return getMessage();
    }

    private static String defaultMessage(ErrorCode errorCode, Object[] args) {
        return args.length == 0 ? errorCode.code() : errorCode.code() + " " + Arrays.toString(args);
    }
}
