package io.saasforge.platform.ops.controller;

import io.saasforge.platform.ops.service.DeadEventService;
import io.saasforge.platform.ops.service.OpsPermissions;
import io.saasforge.sdk.core.api.ApiResponse;
import io.saasforge.sdk.mq.DeadEventResponse;
import io.saasforge.sdk.security.RequiresPermission;
import io.saasforge.sdk.web.PageQuery;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "平台运维")
@RestController
@RequestMapping("/ops/services/{service}/dead-events")
class DeadEventController {
    private final DeadEventService events;

    DeadEventController(DeadEventService events) {
        this.events = events;
    }

    @Operation(summary = "按服务和状态分页查询死信元数据")
    @RequiresPermission(OpsPermissions.READ)
    @GetMapping
    ApiResponse<List<DeadEventResponse>> list(
            @PathVariable String service, @RequestParam(defaultValue = "PENDING") String status, PageQuery page) {
        return events.list(service, status, page);
    }

    @Operation(summary = "读取死信元数据和最新错误")
    @RequiresPermission(OpsPermissions.READ)
    @GetMapping("/{id}")
    ApiResponse<DeadEventResponse> get(@PathVariable String service, @PathVariable UUID id) {
        return events.get(service, id);
    }

    @Operation(summary = "在所属服务重放死信")
    @RequiresPermission(OpsPermissions.MANAGE)
    @PostMapping("/{id}/replay")
    ApiResponse<DeadEventResponse> replay(
            @PathVariable String service, @PathVariable UUID id, @RequestBody ResolutionRequest request) {
        return events.replay(service, id, request.reason());
    }

    @Operation(summary = "放弃待处置死信")
    @RequiresPermission(OpsPermissions.MANAGE)
    @PostMapping("/{id}/abandon")
    ApiResponse<DeadEventResponse> abandon(
            @PathVariable String service, @PathVariable UUID id, @RequestBody ResolutionRequest request) {
        return events.abandon(service, id, request.reason());
    }

    record ResolutionRequest(String reason) {}
}
