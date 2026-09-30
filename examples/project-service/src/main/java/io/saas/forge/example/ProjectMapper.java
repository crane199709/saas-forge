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

    record NewProject(UUID tenant, String name, String description) {}
    record ProjectRow(String id, String name, String description, long version, Instant createdAt, Instant updatedAt) {}
}
