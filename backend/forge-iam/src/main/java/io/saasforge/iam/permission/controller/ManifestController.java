package io.saasforge.iam.permission.controller;

import io.saasforge.iam.permission.service.PermissionService;
import io.saasforge.sdk.core.ResourceManifest;
import io.saasforge.sdk.core.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ManifestController {
    private final PermissionService permissions;

    ManifestController(PermissionService permissions) {
        this.permissions = permissions;
    }

    // 请求体即 SDK 生成的清单契约 ResourceManifest，由 forge-sdk-core 定义，iam 与 SDK 共用。
    @Operation(summary = "业务服务上报 Resource Manifest；新版本返回 201，同一版本重复上报相同内容返回 200")
    @PostMapping(value = "/internal/manifests", consumes = "application/json")
    ApiResponse<ManifestReportResponse> report(@RequestBody ResourceManifest manifest, Authentication service) {
        var response = new ManifestReportResponse(manifest.service(), manifest.version());
        return permissions.report(service.getName(), manifest)
                ? ApiResponse.created(response)
                : ApiResponse.ok(response);
    }

    // 服务间接口，网关拒绝 /internal/**。forge-platform 据此为套餐的 Feature Pack 提供可选的权限码。
    @Operation(summary = "可纳入套餐 Feature Pack 的权限码：业务服务申报的有效权限码，不含 iam 自身的管理权限码")
    @GetMapping("/internal/permissions")
    ApiResponse<List<PermissionResponse>> permissions() {
        return ApiResponse.ok(permissions.planScopedPermissions().stream()
                .map(row -> new PermissionResponse(row.code(), row.name(), row.service(), row.group()))
                .toList());
    }

    record ManifestReportResponse(String service, String version) {}

    record PermissionResponse(String code, String name, String service, String group) {}
}
