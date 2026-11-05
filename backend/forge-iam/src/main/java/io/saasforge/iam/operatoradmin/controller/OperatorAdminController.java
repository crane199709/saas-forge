package io.saasforge.iam.operatoradmin.controller;

import io.saasforge.iam.operator.model.OperatorRow;
import io.saasforge.iam.operatoradmin.model.ManagedOperator;
import io.saasforge.iam.operatoradmin.service.OperatorManagementService;
import io.saasforge.sdk.core.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// 只接受 Platform Realm Token（网关按 /iam/platform/** 校验，iam 以 Platform Realm 的解码器验签），操作人取自 Token 的 sub。
@Tag(name = "运营账号")
@RestController
@RequestMapping("/platform/operators")
class OperatorAdminController {
    private final OperatorManagementService operators;

    OperatorAdminController(OperatorManagementService operators) {
        this.operators = operators;
    }

    @Operation(summary = "列出运营账号及其平台角色")
    @GetMapping
    ApiResponse<List<OperatorResponse>> list(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(
                operators.list(actor(jwt)).stream().map(OperatorResponse::from).toList());
    }

    @Operation(summary = "创建运营账号并分配平台角色")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<OperatorResponse> create(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateOperatorRequest request) {
        return ApiResponse.created(OperatorResponse.from(operators.create(
                actor(jwt),
                request.account(),
                request.displayName().strip(),
                request.password(),
                region(request.region()),
                request.roleIds())));
    }

    @Operation(summary = "修改显示名与负责区域；负责区域变化后该运营人员下一次请求起按新数据范围处理")
    @PutMapping("/{id}")
    ApiResponse<Void> update(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateOperatorRequest request) {
        operators.update(actor(jwt), id, request.displayName().strip(), region(request.region()));
        return ApiResponse.ok();
    }

    @Operation(summary = "停用运营账号：已签发的 Token 立即失效，不能再登录或刷新；不能停用最后一位超级管理员")
    @PostMapping("/{id}/disable")
    ApiResponse<Void> disable(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        operators.disable(actor(jwt), id);
        return ApiResponse.ok();
    }

    @Operation(summary = "恢复已停用的运营账号")
    @PostMapping("/{id}/enable")
    ApiResponse<Void> enable(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        operators.enable(actor(jwt), id);
        return ApiResponse.ok();
    }

    @Operation(summary = "重置密码；已签发的 Access Token 随即失效")
    @PutMapping("/{id}/password")
    ApiResponse<Void> resetPassword(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody PasswordRequest request) {
        operators.resetPassword(actor(jwt), id, request.password());
        return ApiResponse.ok();
    }

    @Operation(summary = "整体替换运营人员的平台角色；该运营人员下一次请求起按新权限处理")
    @PutMapping("/{id}/roles")
    ApiResponse<Void> assign(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestBody AssignRequest request) {
        operators.assign(actor(jwt), id, request.roleIds());
        return ApiResponse.ok();
    }

    private static UUID actor(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    // 空白的负责区域视为未分配。
    private static String region(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /**
     * @param account 登录账号，创建后不可修改
     * @param region 负责的区域，可引用为平台 Data Policy 的 {@code subject.attributes.region}；不填为未分配
     */
    record CreateOperatorRequest(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{2,119}")
            String account,

            @NotBlank @Size(max = 120) String displayName,
            @NotBlank @Size(min = 8, max = 72) String password,
            @Size(max = 64) String region,
            List<UUID> roleIds) {
        CreateOperatorRequest {
            roleIds = roleIds == null ? List.of() : roleIds;
        }

        @Override
        public String toString() {
            return "CreateOperatorRequest[account=" + account + "]";
        }
    }

    record UpdateOperatorRequest(
            @NotBlank @Size(max = 120) String displayName,
            @Size(max = 64) String region) {}

    // 长度等规则由 Password Policy 校验并返回 password.*，这里只限制请求体大小。
    record PasswordRequest(@NotNull @Size(max = 256) String password) {
        @Override
        public String toString() {
            return "PasswordRequest[]";
        }
    }

    record AssignRequest(List<UUID> roleIds) {
        AssignRequest {
            roleIds = roleIds == null ? List.of() : roleIds;
        }
    }

    /**
     * @param status {@code active} 或 {@code disabled}
     * @param roleIds 持有的平台角色
     */
    record OperatorResponse(
            UUID id,
            String account,
            String displayName,
            String region,
            String status,
            List<UUID> roleIds,
            OffsetDateTime createdAt) {
        static OperatorResponse from(ManagedOperator value) {
            OperatorRow operator = value.operator();
            return new OperatorResponse(
                    operator.id(),
                    operator.account(),
                    operator.displayName(),
                    operator.region(),
                    operator.status(),
                    value.roleIds(),
                    operator.createdAt());
        }
    }
}
