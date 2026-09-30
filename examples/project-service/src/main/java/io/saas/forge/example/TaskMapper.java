package io.saas.forge.example;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;

@Mapper
interface TaskMapper {
    TaskRow insert(NewTask task);
    TaskRow find(TaskId task);
    List<TaskRow> list(TaskQuery query);

    record NewTask(UUID tenant, UUID project, String title, String description) {}
    record TaskId(UUID project, UUID id) {}
    record TaskQuery(UUID project, UUID after, int limit) {}
    record TaskRow(String id, String projectId, String title, String description, String status,
                   long version, Instant createdAt, Instant updatedAt) {}
}
