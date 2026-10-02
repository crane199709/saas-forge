package io.saas.forge.example;

import io.saas.forge.sdk.tenant.TenantContextAccessor;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ProjectService {
    private final TenantContextAccessor tenants;
    private final ProjectRepository projects;
    private final ProjectWriteRepository writes;
    private final ProjectFactOutbox outbox;
    private final tools.jackson.databind.ObjectMapper json;

    ProjectService(TenantContextAccessor tenants, ProjectRepository projects, ProjectWriteRepository writes,
            tools.jackson.databind.ObjectMapper json, ProjectFactOutbox outbox) {
        this.outbox = outbox;
        this.tenants = tenants;
        this.projects = projects;
        this.writes = writes;
        this.json = json;
    }

    @Transactional
    public ProjectWriteResult create(UUID idempotencyKey, CreateProjectRequest request) {
        var context = tenants.requireCurrent();
        projects.tenant(context.tenantId());
        var key = new ProjectWriteRepository.WriteKey(context.identityId(), idempotencyKey, context.tenantId(),
                ProjectWriteRepository.fingerprint(json.writeValueAsString(request)));
        var replay = writes.begin(key);
        if (replay.isPresent()) return replay.get();
        var project = projects.create(context.tenantId(), request);
        var result = new ProjectWriteResult(201, json.writeValueAsString(project), "/api/v1/projects/" + project.id());
        outbox.append(context, "project", "created", project.id(), project.id(), project.version());
        writes.complete(key, result);
        return result;
    }

    @Transactional(readOnly = true)
    public ProjectPage list(String cursor, int limit) {
        var context = tenants.requireCurrent();
        projects.tenant(context.tenantId());
        UUID after = null;
        if (cursor != null) {
            try {
                if (cursor.isEmpty() || cursor.length() > 256) throw ProjectException.invalidPage();
                byte[] decoded = java.util.Base64.getUrlDecoder().decode(cursor);
                if (!java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(cursor))
                    throw ProjectException.invalidPage();
                String[] fields = new String(decoded, java.nio.charset.StandardCharsets.UTF_8).split(":", -1);
                if (fields.length != 4 || !fields[0].equals("projects-v1")
                        || !fields[1].equals(context.tenantId().toString()) || !fields[2].equals(Integer.toString(limit)))
                    throw ProjectException.invalidPage();
                after = UUID.fromString(fields[3]);
                if (after.version() != 7 || !after.toString().equals(fields[3]) || projects.find(after).isEmpty())
                    throw ProjectException.invalidPage();
            } catch (IllegalArgumentException invalid) { throw ProjectException.invalidPage(); }
        }
        var rows = projects.list(after, limit + 1);
        boolean hasMore = rows.size() > limit;
        var items = hasMore ? rows.subList(0, limit) : rows;
        String next = hasMore ? java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("projects-v1:" + context.tenantId() + ":" + limit + ":" + items.get(items.size() - 1).id())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)) : null;
        return new ProjectPage(items, next, hasMore);
    }

    @Transactional
    public ProjectWriteResult update(UUID id, UUID idempotencyKey, long version, CreateProjectRequest request,
            String traceId) {
        var context = tenants.requireCurrent();
        projects.tenant(context.tenantId());
        var key = new ProjectWriteRepository.WriteKey(context.identityId(), idempotencyKey, context.tenantId(),
                ProjectWriteRepository.fingerprint("PUT", "/api/v1/projects/" + id, version + "\n" + json.writeValueAsString(request)));
        var replay = writes.begin(key);
        if (replay.isPresent()) return replay.get();
        var updated = projects.update(id, version, request);
        ProjectWriteResult result;
        if (updated.isPresent()) result = new ProjectWriteResult(200, json.writeValueAsString(updated.get()), null);
        else {
            boolean exists = projects.find(id).isPresent();
            int status = exists ? 409 : 404;
            String code = exists ? "RESOURCE_VERSION_CONFLICT" : "PROJECT_NOT_FOUND";
            String detail = exists ? "The project version has changed." : "Project not found.";
            result = new ProjectWriteResult(status, json.writeValueAsString(ProjectProblem.of(status, code, detail, traceId, null)), null);
        }
        if (updated.isPresent()) outbox.append(context, "project", "updated", id, id, updated.get().version());
        writes.complete(key, result);
        return result;
    }

    @Transactional
    public ProjectWriteResult delete(UUID id, UUID idempotencyKey, long version, String traceId) {
        var context = tenants.requireCurrent();
        projects.tenant(context.tenantId());
        var key = new ProjectWriteRepository.WriteKey(context.identityId(), idempotencyKey, context.tenantId(),
                ProjectWriteRepository.fingerprint("DELETE", "/api/v1/projects/" + id, Long.toString(version)));
        var replay = writes.begin(key);
        if (replay.isPresent()) return replay.get();
        var project = projects.lockForDeletion(id);
        ProjectWriteResult result;
        if (project.isEmpty()) result = deletionFailure(404, "PROJECT_NOT_FOUND", "Project not found.", traceId);
        else if (project.get().version() != version)
            result = deletionFailure(409, "RESOURCE_VERSION_CONFLICT", "The project version has changed.", traceId);
        else if (projects.hasTasks(id))
            result = deletionFailure(409, "PROJECT_NOT_EMPTY", "Delete all tasks before deleting the project.", traceId);
        else {
            if (!projects.delete(id, version)) throw new IllegalStateException("已锁定 Project 删除失败");
            result = new ProjectWriteResult(204, "", null);
        }
        if (result.status() == 204) outbox.append(context, "project", "deleted", id, id, version);
        writes.complete(key, result);
        return result;
    }

    private ProjectWriteResult deletionFailure(int status, String code, String detail, String traceId) {
        return new ProjectWriteResult(status, json.writeValueAsString(ProjectProblem.of(status, code, detail, traceId, null)), null);
    }

    @Transactional(readOnly = true)
    public ProjectResult get(UUID id) {
        var context = tenants.requireCurrent();
        projects.tenant(context.tenantId());
        return projects.find(id).orElseThrow(() -> new ProjectException(404, "PROJECT_NOT_FOUND", "Project not found."));
    }
}
