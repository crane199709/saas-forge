package io.saas.forge.example;

import java.time.Instant;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
interface ProjectMapper {
    String setTenant(String tenant);
    ProjectRow insert(NewProject project);
    ProjectRow find(@Param("id") UUID id);

    java.util.List<ProjectRow> list(PageQuery query);
    ProjectRow update(ProjectUpdate update);
    ProjectRow lockForDeletion(@Param("id") UUID id);
    ProjectRow lockForTaskCreation(@Param("id") UUID id);
    boolean hasTasks(@Param("id") UUID id);
    int delete(ProjectVersion project);

    record ProjectVersion(UUID id, long version) {}

    record PageQuery(UUID after, int limit) {}
    record ProjectUpdate(UUID id, long version, String name, String description) {}
    record NewProject(UUID tenant, String name, String description) {}
    record ProjectRow(String id, String name, String description, long version, Instant createdAt, Instant updatedAt) {}
}
