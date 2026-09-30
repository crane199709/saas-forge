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
    private final tools.jackson.databind.ObjectMapper json;

    ProjectService(TenantContextAccessor tenants, ProjectRepository projects, ProjectWriteRepository writes,
            tools.jackson.databind.ObjectMapper json) {
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
        writes.lock(key);
        writes.expire(key);
        if (!writes.claim(key)) {
            // RLS 隐藏另一 Tenant 的历史正文；唯一键仍阻止同一 Identity 跨 Tenant 复用。
            var existing = writes.find(key).orElseThrow(ProjectService::reusedKey);
            if (!existing.fingerprint().equals(key.fingerprint())) throw reusedKey();
            if (existing.result() == null)
                throw new ProjectException(409, "IDEMPOTENCY_REQUEST_IN_PROGRESS", "The request is still in progress.");
            return existing.result();
        }
        var project = projects.create(context.tenantId(), request);
        var result = new ProjectWriteResult(201, json.writeValueAsString(project), "/api/v1/projects/" + project.id());
        writes.complete(key, result);
        return result;
    }

    private static ProjectException reusedKey() {
        return new ProjectException(409, "IDEMPOTENCY_KEY_REUSED", "The key is already bound to another request.");
    }

    @Transactional(readOnly = true)
    public ProjectResult get(UUID id) {
        var context = tenants.requireCurrent();
        projects.tenant(context.tenantId());
        return projects.find(id).orElseThrow(() -> new ProjectException(404, "PROJECT_NOT_FOUND", "Project not found."));
    }
}
