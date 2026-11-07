package io.saasforge.platform.ops.controller;

import io.saasforge.platform.ops.model.OpsServiceStatus;
import io.saasforge.platform.ops.service.OpsClient;
import io.saasforge.platform.ops.service.OpsPermissions;
import io.saasforge.sdk.core.api.ApiResponse;
import io.saasforge.sdk.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "平台运维")
@RestController
class OpsController {
    private final OpsClient client;

    OpsController(OpsClient client) {
        this.client = client;
    }

    @Operation(summary = "已上报服务及其运维能力可用状态")
    @RequiresPermission(OpsPermissions.READ)
    @GetMapping("/ops/services")
    ApiResponse<List<OpsServiceStatus>> services() {
        return ApiResponse.ok(client.services());
    }
}
