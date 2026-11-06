package io.saasforge.iam.password.controller;

import io.saasforge.iam.mfa.model.RecoveryCodeFormat;
import io.saasforge.iam.password.model.ResetTicket;
import io.saasforge.iam.password.service.PasswordService;
import io.saasforge.iam.verification.model.CodeDelivery;
import io.saasforge.iam.verification.model.SendCodeRequest;
import io.saasforge.sdk.core.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 自助改密码与忘记密码。新密码的长度、弱密码与登录标识规则由 Password Policy 校验并返回 {@code password.*}，请求模型只限制非空与请求体大小。
 */
@Tag(name = "密码")
@RestController
class PasswordController {
    private final PasswordService passwords;

    PasswordController(PasswordService passwords) {
        this.passwords = passwords;
    }

    @Operation(summary = "Identity 凭原密码改密码；其他会话与 Refresh Token 随即失效，已签发的 Access Token（含当前这个）失效，当前 Refresh Token 可继续刷新")
    @PostMapping(value = "/auth/password/change", consumes = "application/json")
    ApiResponse<Void> changeIdentityPassword(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ChangeRequest body) {
        passwords.changeIdentityPassword(
                UUID.fromString(jwt.getSubject()), body.currentPassword(), body.newPassword(), jwt.getTokenValue());
        return ApiResponse.ok();
    }

    @Operation(summary = "Operator 凭原密码改密码；其他会话与 Refresh Token 随即失效，已签发的 Access Token（含当前这个）失效，当前 Refresh Token 可继续刷新")
    @PostMapping(value = "/platform/auth/password/change", consumes = "application/json")
    ApiResponse<Void> changeOperatorPassword(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ChangeRequest body) {
        passwords.changeOperatorPassword(
                UUID.fromString(jwt.getSubject()), body.currentPassword(), body.newPassword(), jwt.getTokenValue());
        return ApiResponse.ok();
    }

    @Operation(summary = "忘记密码：向已登记的邮箱或手机号发送验证码，目标是否已注册的响应相同")
    @PostMapping(value = "/auth/password/forgot/send-code", consumes = "application/json")
    ApiResponse<CodeDelivery> sendResetCode(@Valid @RequestBody SendCodeRequest body) {
        return ApiResponse.ok(passwords.sendResetCode(body.target()));
    }

    @Operation(summary = "忘记密码：校验验证码，换取一次性重设凭证，并告知是否还须提交动态码或 Recovery Code")
    @PostMapping(value = "/auth/password/forgot/verify", consumes = "application/json")
    ApiResponse<ResetTicket> verifyResetCode(@Valid @RequestBody VerifyRequest body) {
        return ApiResponse.ok(passwords.verifyResetCode(body.target(), body.code()));
    }

    @Operation(summary = "忘记密码：以重设凭证设置新密码，已绑定 Authenticator 时须同时提供动态码或 Recovery Code；该身份的全部会话与 Token 随即失效")
    @PostMapping(value = "/auth/password/forgot/reset", consumes = "application/json")
    ApiResponse<Void> reset(@Valid @RequestBody ResetRequest body) {
        passwords.reset(body.resetToken(), body.password(), body.code(), body.recoveryCode());
        return ApiResponse.ok();
    }

    // 新密码只限制请求体大小，规则交给 Password Policy，以便返回 password.* 错误码。
    record ChangeRequest(
            @NotBlank @Size(max = 256) String currentPassword,
            @NotNull @Size(max = 256) String newPassword) {
        @Override
        public String toString() {
            return "ChangeRequest[redacted]";
        }
    }

    record VerifyRequest(
            @NotBlank @Size(max = 254) @Pattern(regexp = SendCodeRequest.TARGET_PATTERN)
            String target,

            @NotBlank @Pattern(regexp = SendCodeRequest.CODE_PATTERN)
            String code) {
        @Override
        public String toString() {
            return "VerifyRequest[redacted]";
        }
    }

    /** 动态码与 Recovery Code 至多提供一个；Recovery Code 忽略大小写、空格与连字符。 */
    record ResetRequest(
            @NotBlank @Size(max = 64) String resetToken,
            @NotNull @Size(max = 256) String password,
            @Pattern(regexp = "\\d{6}") String code,

            @RecoveryCodeFormat String recoveryCode) {
        @AssertTrue
        public boolean isAtMostOneFactor() {
            return code == null || recoveryCode == null;
        }

        @Override
        public String toString() {
            return "ResetRequest[redacted]";
        }
    }
}
