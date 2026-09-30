package io.saas.forge.example;

import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
class TaskRepository {
    private static final DateTimeFormatter TIMESTAMP = new DateTimeFormatterBuilder().appendInstant(3).toFormatter();
    private final TaskMapper mapper;
    TaskRepository(TaskMapper mapper) { this.mapper = mapper; }

    TaskResult create(UUID tenant, UUID project, CreateTaskRequest request) {
        return resource(mapper.insert(new TaskMapper.NewTask(tenant, project, request.title(), request.description())));
    }
    Optional<TaskResult> find(UUID project, UUID id) {
        return Optional.ofNullable(mapper.find(new TaskMapper.TaskId(project, id))).map(TaskRepository::resource);
    }
    Optional<TaskResult> update(UUID project, UUID id, long version, UpdateTaskRequest request) {
        return Optional.ofNullable(mapper.update(new TaskMapper.TaskUpdate(project, id, version,
                request.title(), request.description(), request.status().name()))).map(TaskRepository::resource);
    }
    boolean delete(UUID project, UUID id, long version) {
        return mapper.delete(new TaskMapper.TaskVersion(project, id, version)) == 1;
    }
    List<TaskResult> list(UUID project, UUID after, int limit) {
        return mapper.list(new TaskMapper.TaskQuery(project, after, limit)).stream().map(TaskRepository::resource).toList();
    }
    private static TaskResult resource(TaskMapper.TaskRow row) {
        return new TaskResult(UUID.fromString(row.id()), UUID.fromString(row.projectId()), row.title(), row.description(),
                TaskResult.Status.valueOf(row.status()), row.version(), TIMESTAMP.format(row.createdAt()), TIMESTAMP.format(row.updatedAt()));
    }
}
