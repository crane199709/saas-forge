package io.saas.forge.example;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/tasks")
class TaskController {
    private final TaskService tasks;
    TaskController(TaskService tasks) { this.tasks = tasks; }

    @PostMapping(consumes = "application/json", produces = "application/json;charset=UTF-8")
    ResponseEntity<String> create(@PathVariable String projectId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody CreateTaskRequest request, HttpServletRequest http) {
        ProjectController.rejectTenantInput(http);
        if (key == null || key.isBlank()) throw new ProjectException(400, "IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key is required.");
        var result = tasks.create(ProjectController.uuid(projectId, "VALIDATION_FAILED"),
                ProjectController.uuid(key, "IDEMPOTENCY_KEY_INVALID"), request, ProjectProblem.traceId(http.getHeader("traceparent")));
        var response = ResponseEntity.status(result.status()).contentType(MediaType.parseMediaType(
                result.status() < 400 ? "application/json;charset=UTF-8" : "application/problem+json;charset=UTF-8"));
        if (result.location() != null) response.location(URI.create(result.location()));
        return response.body(result.body());
    }

    @PutMapping(path = "/{taskId}", consumes = "application/json", produces = "application/json;charset=UTF-8")
    ResponseEntity<String> update(@PathVariable String projectId, @PathVariable String taskId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = "If-Match", required = false) String version,
            @Valid @RequestBody UpdateTaskRequest request, HttpServletRequest http) {
        ProjectController.rejectTenantInput(http);
        if (key == null || key.isBlank()) throw new ProjectException(400, "IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key is required.");
        return ProjectController.writeResponse(tasks.update(ProjectController.uuid(projectId, "VALIDATION_FAILED"),
                ProjectController.uuid(taskId, "VALIDATION_FAILED"), ProjectController.uuid(key, "IDEMPOTENCY_KEY_INVALID"),
                ProjectController.version(version), request, ProjectExceptionHandler.traceId(http)));
    }

    @DeleteMapping(path = "/{taskId}")
    ResponseEntity<String> delete(@PathVariable String projectId, @PathVariable String taskId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = "If-Match", required = false) String version, HttpServletRequest http) {
        ProjectController.rejectTenantInput(http);
        if (key == null || key.isBlank()) throw new ProjectException(400, "IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key is required.");
        return ProjectController.writeResponse(tasks.delete(ProjectController.uuid(projectId, "VALIDATION_FAILED"),
                ProjectController.uuid(taskId, "VALIDATION_FAILED"), ProjectController.uuid(key, "IDEMPOTENCY_KEY_INVALID"),
                ProjectController.version(version), ProjectExceptionHandler.traceId(http)));
    }

    @GetMapping(path = "/{taskId}", produces = "application/json;charset=UTF-8")
    TaskResult get(@PathVariable String projectId, @PathVariable String taskId, HttpServletRequest http) {
        ProjectController.rejectTenantInput(http);
        return tasks.get(ProjectController.uuid(projectId, "VALIDATION_FAILED"), ProjectController.uuid(taskId, "VALIDATION_FAILED"));
    }

    @GetMapping(produces = "application/json;charset=UTF-8")
    TaskPage list(@PathVariable String projectId, @RequestParam(required = false) String cursor,
            @RequestParam(required = false) String limit, HttpServletRequest http) {
        ProjectController.rejectTenantInput(http, java.util.Set.of("cursor", "limit"));
        if (limit == null) limit = "50";
        if (!limit.matches("[1-9][0-9]{0,2}") || Integer.parseInt(limit) > 100)
            throw new ProjectException(400, "VALIDATION_FAILED", "Limit must be an integer from 1 to 100.");
        return tasks.list(ProjectController.uuid(projectId, "VALIDATION_FAILED"), cursor, Integer.parseInt(limit));
    }
}
