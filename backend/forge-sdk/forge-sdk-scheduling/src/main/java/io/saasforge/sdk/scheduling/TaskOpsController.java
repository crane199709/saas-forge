package io.saasforge.sdk.scheduling;

import io.saasforge.sdk.core.api.ApiResponse;
import io.saasforge.sdk.core.api.ForgeException;
import io.saasforge.sdk.core.api.FrameworkErrorCode;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
final class TaskOpsController {
    private final TaskRegistrar tasks;
    private final TaskRunHistory history;

    TaskOpsController(TaskRegistrar tasks, TaskRunHistory history) {
        this.tasks = tasks;
        this.history = history;
    }

    @GetMapping("/internal/ops/tasks")
    ApiResponse<List<TaskDefinitionResponse>> list() {
        return ApiResponse.ok(tasks.definitions());
    }

    @GetMapping("/internal/ops/tasks/{name}/runs")
    ApiResponse<List<TaskRun>> runs(@PathVariable String name, @RequestParam(defaultValue = "recent") String view) {
        tasks.require(name);
        return ApiResponse.ok(
                switch (view) {
                    case "recent" -> history.recent(name);
                    case "problems" -> history.problems(name);
                    default -> throw new ForgeException(FrameworkErrorCode.REQUEST_INVALID);
                });
    }

    @PostMapping("/internal/ops/tasks/{name}/trigger")
    ApiResponse<TaskTriggerResponse> trigger(@PathVariable String name, @RequestBody TriggerRequest request) {
        return ApiResponse.ok(tasks.trigger(name, request.operatorId(), request.targetTenantId()));
    }

    record TriggerRequest(UUID operatorId, UUID targetTenantId) {}
}
