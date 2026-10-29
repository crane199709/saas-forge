package io.saasforge.platform.tenant.controller;

import io.saasforge.platform.tenant.entity.Tenant;
import io.saasforge.platform.tenant.entity.TenantInitialization;
import io.saasforge.platform.tenant.entity.TenantStatusChange;
import io.saasforge.platform.tenant.service.TenantLifecycleService;
import io.saasforge.platform.tenant.service.TenantService;
import io.saasforge.sdk.core.api.ApiResponse;
import io.saasforge.sdk.web.PageQuery;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// TODO(#22): 平台侧权限接入后按 Operator 的功能权限校验；在此之前任一 Operator 均可管理租户。
@Tag(name = "租户")
@RestController
@RequestMapping("/tenants")
class TenantController {
    private final TenantService tenants;
    private final TenantLifecycleService lifecycle;

    TenantController(TenantService tenants, TenantLifecycleService lifecycle) {
        this.tenants = tenants;
        this.lifecycle = lifecycle;
    }

    @Operation(summary = "创建租户并开始 Provisioning：iam 邀请初始管理员，各服务初始化本服务数据")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<TenantDetailResponse> create(@Valid @RequestBody CreateTenantRequest request) {
        Tenant tenant = tenants.create(
                request.name().strip(),
                request.adminContact().strip(),
                request.status() == null ? TenantLifecycleService.ACTIVE : request.status(),
                request.expiresAt(),
                request.planId());
        return ApiResponse.created(detail(tenant));
    }

    @Operation(summary = "变更租户状态：按合法迁移表迁移并广播；迁移到 deleted 进入冷静期，冷静期内迁移回删除前的状态即撤回删除")
    @PostMapping("/{id}/transitions")
    ApiResponse<TenantDetailResponse> transition(@PathVariable UUID id, @Valid @RequestBody TransitionRequest request) {
        String reason = request.reason() == null || request.reason().isBlank()
                ? null
                : request.reason().strip();
        return ApiResponse.ok(detail(lifecycle.transition(id, request.status(), request.expiresAt(), reason)));
    }

    @Operation(summary = "为租户更换套餐并广播；降级后超出新套餐的授权随即失效但保留记录，升级后恢复")
    @PutMapping("/{id}/plan")
    ApiResponse<TenantDetailResponse> changePlan(@PathVariable UUID id, @Valid @RequestBody ChangePlanRequest request) {
        return ApiResponse.ok(detail(tenants.changePlan(id, request.planId())));
    }

    @Operation(summary = "分页列出租户，最近创建的在前")
    @GetMapping
    ApiResponse<List<TenantResponse>> list(PageQuery query) {
        List<TenantResponse> items = tenants.page(query.offset(), query.size()).stream()
                .map(TenantResponse::from)
                .toList();
        return ApiResponse.page(items, query.toPageMeta(tenants.count()));
    }

    @Operation(summary = "读取租户、各服务的初始化状态、可迁移的状态与状态变更历史")
    @GetMapping("/{id}")
    ApiResponse<TenantDetailResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(detail(tenants.get(id)));
    }

    @Operation(summary = "重试某个服务失败的初始化")
    @PostMapping("/{id}/initializations/{service}/retry")
    ApiResponse<InitializationResponse> retry(@PathVariable UUID id, @PathVariable String service) {
        return ApiResponse.ok(InitializationResponse.from(tenants.retry(id, service)));
    }

    private TenantDetailResponse detail(Tenant tenant) {
        return new TenantDetailResponse(
                TenantResponse.from(tenant),
                tenants.initializations(tenant.getId()).stream()
                        .map(InitializationResponse::from)
                        .toList(),
                lifecycle.allowedTargets(tenant),
                lifecycle.history(tenant.getId()).stream()
                        .map(StatusChangeResponse::from)
                        .toList());
    }

    // 与 iam 的验证码目标格式一致：含 @ 的是邮箱，否则是手机号。
    record CreateTenantRequest(
            @NotBlank @Size(max = 120) String name,

            @NotBlank @Size(max = 254) @Pattern(regexp = "^\\s*(?:[^@\\s]+@[^@\\s]+\\.[^@\\s]+|\\+?[0-9]{6,15})\\s*$")
            String adminContact,

            // 不填为 active
            @Pattern(regexp = "trial|active") String status,

            @Future OffsetDateTime expiresAt,

            @NotNull UUID planId) {}

    record ChangePlanRequest(@NotNull UUID planId) {}

    record TransitionRequest(
            @NotBlank String status, @Size(max = 500) String reason, OffsetDateTime expiresAt) {}

    /**
     * @param planId 适用的套餐；套餐上线前创建的租户为 null，不受套餐限制
     * @param expiresAt 试用或订购的到期时间
     * @param purgeAt 删除状态下冷静期的结束时间
     * @param purgedAt 冷静期已结束、各服务开始清理数据的时间
     */
    record TenantResponse(
            UUID id,
            String name,
            String status,
            String adminContact,
            UUID planId,
            OffsetDateTime expiresAt,
            OffsetDateTime purgeAt,
            OffsetDateTime purgedAt,
            OffsetDateTime createdAt) {
        static TenantResponse from(Tenant tenant) {
            return new TenantResponse(
                    tenant.getId(),
                    tenant.getName(),
                    tenant.getStatus(),
                    tenant.getAdminContact(),
                    tenant.getPlanId(),
                    tenant.getExpiresAt(),
                    tenant.getPurgeAt(),
                    tenant.getPurgedAt(),
                    tenant.getCreatedAt());
        }
    }

    /** @param fromStatus 变更前的状态，创建租户时为 null */
    record StatusChangeResponse(
            String fromStatus, String toStatus, String reason, UUID changedBy, OffsetDateTime changedAt) {
        static StatusChangeResponse from(TenantStatusChange change) {
            return new StatusChangeResponse(
                    change.getFromStatus(),
                    change.getToStatus(),
                    change.getReason(),
                    change.getChangedBy(),
                    change.getChangedAt());
        }
    }

    record InitializationResponse(String service, String status, String error, OffsetDateTime updatedAt) {
        static InitializationResponse from(TenantInitialization initialization) {
            return new InitializationResponse(
                    initialization.getService(),
                    initialization.getStatus(),
                    initialization.getError(),
                    initialization.getUpdatedAt());
        }
    }

    /** @param allowedTransitions 当前可迁移到的状态，冷静期内只有删除前的状态（撤回删除） */
    record TenantDetailResponse(
            TenantResponse tenant,
            List<InitializationResponse> initializations,
            List<String> allowedTransitions,
            List<StatusChangeResponse> statusChanges) {}
}
