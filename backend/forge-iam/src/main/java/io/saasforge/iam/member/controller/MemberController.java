package io.saasforge.iam.member.controller;

import io.saasforge.iam.member.model.ManagedMember;
import io.saasforge.iam.member.service.MemberService;
import io.saasforge.iam.membership.model.MembershipStatus;
import io.saasforge.iam.membership.model.TenantActor;
import io.saasforge.iam.verification.model.SendCodeRequest;
import io.saasforge.sdk.core.api.ApiResponse;
import io.saasforge.sdk.web.PageQuery;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "成员")
@RestController
@RequestMapping("/members")
class MemberController {
    private final MemberService members;

    MemberController(MemberService members) {
        this.members = members;
    }

    @Operation(summary = "分页列出当前租户的成员，含邀请中与已离开的成员；可按关键词搜索、按状态筛选")
    @GetMapping
    ApiResponse<List<MemberResponse>> list(
            @AuthenticationPrincipal Jwt jwt,
            PageQuery query,
            @RequestParam(required = false) @Size(max = 120) String keyword,
            @RequestParam(required = false) @Pattern(regexp = "^(invited|active|suspended|left)$") String status) {
        TenantActor actor = TenantActor.of(jwt);
        String term = keyword == null || keyword.isBlank() ? null : keyword.strip();
        return ApiResponse.page(
                members.search(actor, term, status, query.offset(), query.size()).stream()
                        .map(MemberResponse::from)
                        .toList(),
                query.toPageMeta(members.count(actor, term, status)));
    }

    @Operation(summary = "按邮箱或手机号邀请成员，可预设角色与主部门；不存在的联系方式收到注册邀请")
    @PostMapping("/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<InvitedResponse> invite(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody InviteRequest request) {
        MembershipStatus invited = members.invite(
                TenantActor.of(jwt), request.contact().strip(), request.roleIds(), request.primaryDepartmentId());
        return ApiResponse.created(new InvitedResponse(invited.membershipId(), invited.status()));
    }

    @Operation(summary = "停用成员，立即生效；Owner 不可停用")
    @PostMapping("/{membershipId}/suspend")
    ApiResponse<Void> suspend(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID membershipId) {
        members.suspend(TenantActor.of(jwt), membershipId);
        return ApiResponse.ok();
    }

    @Operation(summary = "恢复停用的成员")
    @PostMapping("/{membershipId}/resume")
    ApiResponse<Void> resume(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID membershipId) {
        members.resume(TenantActor.of(jwt), membershipId);
        return ApiResponse.ok();
    }

    @Operation(summary = "移除成员或撤回邀请：成员变为已离开，角色与组织分配一并清除；Owner 不可移除")
    @DeleteMapping("/{membershipId}")
    ApiResponse<Void> remove(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID membershipId) {
        members.remove(TenantActor.of(jwt), membershipId);
        return ApiResponse.ok();
    }

    @Operation(summary = "主动退出当前租户；Owner 须先转让")
    @PostMapping("/me/leave")
    ApiResponse<Void> leave(@AuthenticationPrincipal Jwt jwt) {
        members.leave(TenantActor.of(jwt));
        return ApiResponse.ok();
    }

    @Operation(summary = "Owner 把所有者身份转让给另一个正常状态的成员，原 Owner 降为 Tenant Admin")
    @PutMapping("/owner")
    ApiResponse<Void> transferOwner(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody TransferRequest request) {
        members.transferOwner(TenantActor.of(jwt), request.membershipId());
        return ApiResponse.ok();
    }

    /**
     * @param contact 邮箱或手机号，格式与注册一致
     * @param roleIds 预设角色，不含 Owner；需要 {@code iam:role:manage}
     * @param primaryDepartmentId 预设主部门；需要 {@code iam:org:manage}
     */
    record InviteRequest(
            @NotBlank @Size(max = 254) @Pattern(regexp = SendCodeRequest.TARGET_PATTERN)
            String contact,

            List<UUID> roleIds,
            UUID primaryDepartmentId) {
        InviteRequest {
            roleIds = roleIds == null ? List.of() : roleIds;
        }
    }

    record TransferRequest(@NotNull UUID membershipId) {}

    record InvitedResponse(UUID membershipId, String status) {}

    record MemberResponse(
            UUID membershipId,
            String account,
            String displayName,
            String email,
            String phone,
            String status,
            boolean owner) {
        static MemberResponse from(ManagedMember value) {
            return new MemberResponse(
                    value.membershipId(),
                    value.account(),
                    value.displayName(),
                    value.email(),
                    value.phone(),
                    value.status(),
                    value.owner());
        }
    }
}
