package io.saasforge.iam.registration.controller;

import io.saasforge.iam.registration.service.RegistrationService;
import io.saasforge.iam.verification.model.CodeDelivery;
import io.saasforge.iam.verification.model.SendCodeRequest;
import io.saasforge.sdk.core.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "注册")
@RestController
class RegistrationController {
    private final RegistrationService registrations;

    RegistrationController(RegistrationService registrations) {
        this.registrations = registrations;
    }

    @Operation(summary = "向待注册的邮箱或手机号发送注册验证码")
    @PostMapping(value = "/auth/register/send-code", consumes = "application/json")
    ApiResponse<CodeDelivery> sendCode(@Valid @RequestBody SendCodeRequest request) {
        return ApiResponse.ok(registrations.sendCode(request.target()));
    }

    @Operation(summary = "以邮箱或手机验证码注册全局 Identity，注册后尚未加入任何租户")
    @PostMapping(value = "/auth/register", consumes = "application/json")
    ApiResponse<RegisterResponse> register(@Valid @RequestBody RegisterRequest request) {
        UUID id = registrations.register(
                request.account(), request.password(), request.displayName(), request.target(), request.code());
        return ApiResponse.created(new RegisterResponse(id));
    }

    // 账号以字母开头且不含 @，与邮箱、手机号两种登录标识不会混淆。
    record RegisterRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_.-]{2,63}$")
            String account,

            @NotBlank @Size(min = 8, max = 72) String password,
            @Size(max = 120) String displayName,

            @NotBlank @Size(max = 254) @Pattern(regexp = SendCodeRequest.TARGET_PATTERN)
            String target,

            @NotBlank @Pattern(regexp = SendCodeRequest.CODE_PATTERN)
            String code) {
        @Override
        public String toString() {
            return "RegisterRequest[account=" + account + "]";
        }
    }

    record RegisterResponse(UUID identityId) {}
}
