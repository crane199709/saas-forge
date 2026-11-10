package io.saasforge.iam.role.controller;

import io.saasforge.iam.role.model.PermissionTreeResponse;
import io.saasforge.iam.role.service.PermissionTreeService;
import io.saasforge.sdk.core.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

// 服务间接口沿用 Resource Manifest 上报令牌；网关拒绝 /internal/**。forge-platform 据此在 Plan 中按 Sub-App 勾选权限码。
@Tag(name = "内部权限清单")
@RestController
class InternalPermissionTreeController {
    private final PermissionTreeService permissionTrees;

    InternalPermissionTreeController(PermissionTreeService permissionTrees) {
        this.permissionTrees = permissionTrees;
    }

    @Operation(summary = "Feature Pack 的菜单授权树：业务服务的菜单与可纳入套餐的权限码，不按 Plan 或 Sub-App 裁剪")
    @GetMapping("/internal/permission-tree")
    ApiResponse<PermissionTreeResponse> permissionTree() {
        return ApiResponse.ok(permissionTrees.planCatalog());
    }
}
