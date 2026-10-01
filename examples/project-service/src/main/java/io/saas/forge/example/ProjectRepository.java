package io.saas.forge.example;

import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
class ProjectRepository {
    private static final DateTimeFormatter TIMESTAMP = new DateTimeFormatterBuilder().appendInstant(3).toFormatter();
    private final ProjectMapper mapper;

    ProjectRepository(ProjectMapper mapper) { this.mapper = mapper; }

    void tenant(UUID tenant) { mapper.setTenant(tenant.toString()); }

    ProjectResult create(UUID tenant, CreateProjectRequest request) {
        return resource(mapper.insert(new ProjectMapper.NewProject(tenant, request.name(), request.description())));
    }

    Optional<ProjectResult> find(UUID id) { return Optional.ofNullable(mapper.find(id)).map(ProjectRepository::resource); }

    java.util.List<ProjectResult> list(UUID after, int limit) {
        return mapper.list(new ProjectMapper.PageQuery(after, limit)).stream().map(ProjectRepository::resource).toList();
    }

    Optional<ProjectResult> update(UUID id, long version, CreateProjectRequest request) {
        return Optional.ofNullable(mapper.update(new ProjectMapper.ProjectUpdate(id, version, request.name(), request.description())))
                .map(ProjectRepository::resource);
    }

    Optional<ProjectResult> lockForDeletion(UUID id) {
        return Optional.ofNullable(mapper.lockForDeletion(id)).map(ProjectRepository::resource);
    }

    boolean lockForTaskCreation(UUID id) { return mapper.lockForTaskCreation(id) != null; }
    boolean hasTasks(UUID id) { return mapper.hasTasks(id); }
    boolean delete(UUID id, long version) { return mapper.delete(new ProjectMapper.ProjectVersion(id, version)) == 1; }

    private static ProjectResult resource(ProjectMapper.ProjectRow row) {
        return new ProjectResult(UUID.fromString(row.id()), row.name(), row.description(), row.version(),
                TIMESTAMP.format(row.createdAt()), TIMESTAMP.format(row.updatedAt()));
    }
}
