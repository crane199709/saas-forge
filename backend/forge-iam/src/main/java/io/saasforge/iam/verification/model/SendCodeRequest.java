package io.saasforge.iam.verification.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 请求发送验证码；注册与登录共用，格式常量也供带验证码的请求复用，保持两处校验一致。 */
public record SendCodeRequest(
        @NotBlank @Size(max = 254) @Pattern(regexp = TARGET_PATTERN)
        String target) {
    /** 含 {@code @} 的目标是邮箱，否则是手机号。 */
    public static final String TARGET_PATTERN = "^(?:[^@\\s]+@[^@\\s]+\\.[^@\\s]+|\\+?[0-9]{6,15})$";

    /** 6 位数字验证码。 */
    public static final String CODE_PATTERN = "^[0-9]{6}$";
}
