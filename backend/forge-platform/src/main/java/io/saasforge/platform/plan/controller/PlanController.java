package io.saasforge.platform.plan.controller;

import io.saasforge.platform.plan.entity.Plan;
import io.saasforge.platform.plan.model.CatalogPermission;
import io.saasforge.platform.plan.model.CatalogPermissionTree;
import io.saasforge.platform.plan.service.PlanService;
import io.saasforge.sdk.core.api.ApiResponse;
import io.saasforge.sdk.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// 套餐列表也供租户面板选择套餐，只要求已认证；修改与 Feature Pack 目录要求管理权限。
@Tag(name = "套餐")
@RestController
@RequestMapping("/plans")
class PlanController {
    static final String MANAGE = "platform:plan:manage";

    private final PlanService plans;

    PlanController(PlanService plans) {
        this.plans = plans;
    }

    @Operation(summary = "列出全部套餐，按编码排序")
    @GetMapping
    ApiResponse<List<PlanResponse>> list() {
        return ApiResponse.ok(plans.list().stream().map(PlanResponse::from).toList());
    }

    @Operation(summary = "Feature Pack 可选的权限码：各业务服务 Resource Manifest 中当前有效的权限码，iam 自身的管理权限码不受套餐限制、不在其中")
    @GetMapping("/permissions")
    @RequiresPermission(MANAGE)
    ApiResponse<List<CatalogPermission>> permissions() {
        return ApiResponse.ok(plans.catalog());
    }

    @Operation(summary = "Feature Pack 的菜单授权树：各业务服务的菜单与可纳入套餐的权限码，不按 Plan 或 Sub-App 裁剪，未挂在菜单上的权限码在 others 中")
    @GetMapping("/permission-tree")
    @RequiresPermission(MANAGE)
    ApiResponse<CatalogPermissionTree> permissionTree() {
        return ApiResponse.ok(plans.catalogTree());
    }

    @Operation(summary = "新建套餐")
    @PostMapping
    @RequiresPermission(MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<PlanResponse> create(@Valid @RequestBody CreatePlanRequest request) {
        return ApiResponse.created(PlanResponse.from(plans.create(
                request.code(),
                request.name().strip(),
                request.permissions(),
                request.subApps(),
                request.memberQuota(),
                request.storageQuotaMb())));
    }

    @Operation(summary = "整体替换套餐的名称、Feature Pack 与 Quota，并向使用它的租户广播；降级后超出的授权随即失效，升级后恢复")
    @PutMapping("/{id}")
    @RequiresPermission(MANAGE)
    ApiResponse<PlanResponse> update(@PathVariable UUID id, @Valid @RequestBody UpdatePlanRequest request) {
        return ApiResponse.ok(PlanResponse.from(plans.update(
                id,
                request.name().strip(),
                request.permissions(),
                request.subApps(),
                request.memberQuota(),
                request.storageQuotaMb())));
    }

    @Operation(summary = "删除套餐；仍有租户使用时拒绝")
    @DeleteMapping("/{id}")
    @RequiresPermission(MANAGE)
    ApiResponse<Void> delete(@PathVariable UUID id) {
        plans.delete(id);
        return ApiResponse.ok();
    }

    /**
     * @param permissions Feature Pack 中的权限码，新加入的须在 iam 的当前目录中
     * @param subApps Feature Pack 中的 Sub-App 编码
     * @param memberQuota 成员数上限，不填为不限
     * @param storageQuotaMb 存储容量上限（MiB），不填为不限
     */
    record CreatePlanRequest(
            @NotBlank @Pattern(regexp = "[a-z][a-z0-9-]{0,63}")
            String code,

            @NotBlank @Size(max = 120) String name,
            @NotNull List<@NotBlank @Size(max = 128) String> permissions,
            @NotNull List<@NotBlank @Pattern(regexp = "[a-z][a-z0-9-]{0,63}") String> subApps,
            @Positive Integer memberQuota,
            @Positive Long storageQuotaMb) {}

    /** 字段含义同 {@link CreatePlanRequest}，编码不可修改。 */
    record UpdatePlanRequest(
            @NotBlank @Size(max = 120) String name,
            @NotNull List<@NotBlank @Size(max = 128) String> permissions,
            @NotNull List<@NotBlank @Pattern(regexp = "[a-z][a-z0-9-]{0,63}") String> subApps,
            @Positive Integer memberQuota,
            @Positive Long storageQuotaMb) {}

    record PlanResponse(
            UUID id,
            String code,
            String name,
            List<String> permissions,
            List<String> subApps,
            Integer memberQuota,
            Long storageQuotaMb,
            OffsetDateTime updatedAt) {
        static PlanResponse from(Plan plan) {
            return new PlanResponse(
                    plan.getId(),
                    plan.getCode(),
                    plan.getName(),
                    List.of(plan.getPermissions()),
                    List.of(plan.getSubApps()),
                    plan.getMemberQuota(),
                    plan.getStorageQuotaMb(),
                    plan.getUpdatedAt());
        }
    }
}
