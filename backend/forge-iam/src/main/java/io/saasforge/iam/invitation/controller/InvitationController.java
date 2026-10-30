package io.saasforge.iam.invitation.controller;

import io.saasforge.iam.invitation.model.AcceptedInvitation;
import io.saasforge.iam.invitation.model.InvitationPreview;
import io.saasforge.iam.invitation.service.InvitationService;
import io.saasforge.sdk.core.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

// 令牌放在请求体而不是路径中，避免写入网关与服务的访问日志。
@Tag(name = "邀请")
@RestController
class InvitationController {
    private final InvitationService invitations;

    InvitationController(InvitationService invitations) {
        this.invitations = invitations;
    }

    @Operation(summary = "查看邀请：邀请加入的租户，以及接受时是否需要设置账号与密码")
    @PostMapping(value = "/auth/invitations/preview", consumes = "application/json")
    ApiResponse<InvitationPreview> preview(@Valid @RequestBody PreviewRequest request) {
        return ApiResponse.ok(invitations.preview(request.token()));
    }

    @Operation(summary = "接受邀请；尚无可登录 Identity 的被邀请人同时设置账号与密码")
    @PostMapping(value = "/auth/invitations/accept", consumes = "application/json")
    ApiResponse<AcceptedInvitation> accept(@Valid @RequestBody AcceptRequest request) {
        return ApiResponse.ok(
                invitations.accept(request.token(), request.account(), request.password(), request.displayName()));
    }

    record PreviewRequest(@NotBlank @Size(max = 64) String token) {
        @Override
        public String toString() {
            return "PreviewRequest[]";
        }
    }

    // 账号与密码规则与注册一致；已有凭证的被邀请人不填。
    record AcceptRequest(
            @NotBlank @Size(max = 64) String token,

            @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_.-]{2,63}$")
            String account,

            @Size(min = 8, max = 72) String password,
            @Size(max = 120) String displayName) {
        @Override
        public String toString() {
            return "AcceptRequest[account=" + account + "]";
        }
    }
}
