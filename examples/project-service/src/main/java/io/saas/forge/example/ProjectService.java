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
        var replay = writes.begin(key);
        if (replay.isPresent()) return replay.get();
        var project = projects.create(context.tenantId(), request);
        var result = new ProjectWriteResult(201, json.writeValueAsString(project), "/api/v1/projects/" + project.id());
        writes.complete(key, result);
        return result;
    }

    @Transactional(readOnly = true)
    public ProjectResult get(UUID id) {
        var context = tenants.requireCurrent();
        projects.tenant(context.tenantId());
        return projects.find(id).orElseThrow(() -> new ProjectException(404, "PROJECT_NOT_FOUND", "Project not found."));
    }
}
