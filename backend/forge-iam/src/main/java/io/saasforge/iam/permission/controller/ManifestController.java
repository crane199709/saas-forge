package io.saasforge.iam.permission.controller;

import io.saasforge.iam.permission.service.PermissionService;
import io.saasforge.sdk.core.ResourceManifest;
import io.saasforge.sdk.core.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.security.core.Authentication;
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

    record ManifestReportResponse(String service, String version) {}
}
