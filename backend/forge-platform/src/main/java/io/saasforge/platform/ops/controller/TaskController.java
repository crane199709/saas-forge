package io.saasforge.platform.ops.controller;

import io.saasforge.platform.ops.service.OpsPermissions;
import io.saasforge.platform.ops.service.TaskService;
import io.saasforge.sdk.core.api.ApiResponse;
import io.saasforge.sdk.scheduling.TaskDefinitionResponse;
import io.saasforge.sdk.scheduling.TaskRun;
import io.saasforge.sdk.scheduling.TaskTriggerResponse;
import io.saasforge.sdk.security.RequiresPermission;
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
@RequestMapping("/ops/services/{service}/tasks")
class TaskController {
    private final TaskService tasks;

    TaskController(TaskService tasks) {
        this.tasks = tasks;
    }

    @Operation(summary = "查看服务声明的任务、调度规则与最近运行")
    @RequiresPermission(OpsPermissions.READ)
    @GetMapping
    ApiResponse<List<TaskDefinitionResponse>> list(@PathVariable String service) {
        return tasks.list(service);
    }

    @Operation(summary = "查询最近运行或最近非成功运行及失败租户")
    @RequiresPermission(OpsPermissions.READ)
    @GetMapping("/{name}/runs")
    ApiResponse<List<TaskRun>> runs(
            @PathVariable String service,
            @PathVariable String name,
            @RequestParam(defaultValue = "recent") String view) {
        return tasks.runs(service, name, view);
    }

    @Operation(summary = "异步手动触发任务或重跑单个租户")
    @RequiresPermission(OpsPermissions.MANAGE)
    @PostMapping("/{name}/trigger")
    ApiResponse<TaskTriggerResponse> trigger(
            @PathVariable String service, @PathVariable String name, @RequestBody TriggerRequest request) {
        return tasks.trigger(service, name, request.targetTenantId());
    }

    record TriggerRequest(UUID targetTenantId) {}
}
