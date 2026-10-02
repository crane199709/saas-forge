package io.saas.forge.example;

import io.saas.forge.sdk.tenant.TenantContextAccessor;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class TaskService {
    private final TenantContextAccessor tenants;
    private final ProjectRepository projects;
    private final TaskRepository tasks;
    private final ProjectWriteRepository writes;
    private final ProjectFactOutbox outbox;
    private final tools.jackson.databind.ObjectMapper json;
    private final TaskCursor cursors = new TaskCursor(Clock.systemUTC());

    TaskService(TenantContextAccessor tenants, ProjectRepository projects, TaskRepository tasks,
            ProjectWriteRepository writes, tools.jackson.databind.ObjectMapper json, ProjectFactOutbox outbox) {
        this.outbox = outbox;
        this.tenants = tenants; this.projects = projects; this.tasks = tasks; this.writes = writes; this.json = json;
    }

    @Transactional
    public ProjectWriteResult create(UUID project, UUID idempotencyKey, CreateTaskRequest request, String traceId) {
        var context = tenants.requireCurrent();
        projects.tenant(context.tenantId());
        String path = "/api/v1/projects/" + project + "/tasks";
        var key = new ProjectWriteRepository.WriteKey(context.identityId(), idempotencyKey, context.tenantId(),
                ProjectWriteRepository.fingerprint(path, json.writeValueAsString(request)));
        var replay = writes.begin(key);
        if (replay.isPresent()) return replay.get();
        ProjectWriteResult result;
        if (!projects.lockForTaskCreation(project)) {
            // 不可见父资源是稳定业务失败，完成记录与成功路径一样同事务提交。
            var problem = new ProjectProblem("urn:saas.forge:problem:project-not-found", "Not Found", 404,
                    "PROJECT_NOT_FOUND", "Project not found.", traceId, null);
            result = new ProjectWriteResult(404, json.writeValueAsString(problem), null);
        } else {
            var task = tasks.create(context.tenantId(), project, request);
            result = new ProjectWriteResult(201, json.writeValueAsString(task), path + "/" + task.id());
            outbox.append(context, "task", "created", task.id(), project, task.version());
        }
        writes.complete(key, result);
        return result;
    }

    @Transactional
    public ProjectWriteResult update(UUID project, UUID id, UUID idempotencyKey, long version,
            UpdateTaskRequest request, String traceId) {
        var context = tenants.requireCurrent();
        projects.tenant(context.tenantId());
        var key = new ProjectWriteRepository.WriteKey(context.identityId(), idempotencyKey, context.tenantId(),
                ProjectWriteRepository.fingerprint("PUT", taskPath(project, id), version + "\n" + json.writeValueAsString(request)));
        var replay = writes.begin(key);
        if (replay.isPresent()) return replay.get();
        var updated = tasks.update(project, id, version, request);
        var result = updated.isPresent() ? new ProjectWriteResult(200, json.writeValueAsString(updated.get()), null)
                : writeFailure(project, id, traceId);
        if (updated.isPresent()) outbox.append(context, "task", "updated", id, project, updated.get().version());
        writes.complete(key, result);
        return result;
    }

    @Transactional
    public ProjectWriteResult delete(UUID project, UUID id, UUID idempotencyKey, long version, String traceId) {
        var context = tenants.requireCurrent();
        projects.tenant(context.tenantId());
        var key = new ProjectWriteRepository.WriteKey(context.identityId(), idempotencyKey, context.tenantId(),
                ProjectWriteRepository.fingerprint("DELETE", taskPath(project, id), Long.toString(version)));
        // 先重放，再检查资源存在性，确保删除后的同键重试仍返回原成功。
        var replay = writes.begin(key);
        if (replay.isPresent()) return replay.get();
        var result = tasks.delete(project, id, version) ? new ProjectWriteResult(204, "", null)
                : writeFailure(project, id, traceId);
        if (result.status() == 204) outbox.append(context, "task", "deleted", id, project, version);
        writes.complete(key, result);
        return result;
    }

    private ProjectWriteResult writeFailure(UUID project, UUID id, String traceId) {
        boolean parentExists = projects.find(project).isPresent();
        boolean taskExists = parentExists && tasks.find(project, id).isPresent();
        int status = taskExists ? 409 : 404;
        String code = taskExists ? "RESOURCE_VERSION_CONFLICT" : parentExists ? "TASK_NOT_FOUND" : "PROJECT_NOT_FOUND";
        String detail = taskExists ? "The task version has changed." : parentExists ? "Task not found." : "Project not found.";
        return new ProjectWriteResult(status, json.writeValueAsString(ProjectProblem.of(status, code, detail, traceId, null)), null);
    }

    private static String taskPath(UUID project, UUID id) {
        return "/api/v1/projects/" + project + "/tasks/" + id;
    }

    @Transactional(readOnly = true)
    public TaskResult get(UUID project, UUID id) {
        var context = tenants.requireCurrent();
        projects.tenant(context.tenantId());
        requireProject(project);
        return tasks.find(project, id).orElseThrow(() -> new ProjectException(404, "TASK_NOT_FOUND", "Task not found."));
    }

    @Transactional(readOnly = true)
    public TaskPage list(UUID project, String cursor, int limit) {
        var context = tenants.requireCurrent();
        projects.tenant(context.tenantId());
        String scope = "tasks:" + context.tenantId() + ":" + project + ":id:asc";
        UUID after = cursors.decode(scope, cursor);
        requireProject(project);
        var rows = tasks.list(project, after, limit + 1);
        boolean more = rows.size() > limit;
        var items = more ? rows.subList(0, limit) : rows;
        return new TaskPage(items, more ? cursors.encode(scope, items.get(items.size() - 1).id()) : null, more);
    }

    private void requireProject(UUID project) {
        if (projects.find(project).isEmpty()) throw new ProjectException(404, "PROJECT_NOT_FOUND", "Project not found.");
    }
}
