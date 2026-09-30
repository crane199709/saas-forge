package io.saas.forge.example;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;

@Mapper
interface TaskMapper {
    TaskRow insert(NewTask task);
    TaskRow find(TaskId task);
    TaskRow update(TaskUpdate task);
    int delete(TaskVersion task);
    List<TaskRow> list(TaskQuery query);

    record NewTask(UUID tenant, UUID project, String title, String description) {}
    record TaskId(UUID project, UUID id) {}
    record TaskVersion(UUID project, UUID id, long version) {}
    record TaskUpdate(UUID project, UUID id, long version, String title, String description, String status) {}
    record TaskQuery(UUID project, UUID after, int limit) {}
    record TaskRow(String id, String projectId, String title, String description, String status,
                   long version, Instant createdAt, Instant updatedAt) {}
}
