package io.saasforge.sdk.io;

import io.saasforge.sdk.core.UuidV7;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** 覆盖导入快照入口；所有读写均要求当前租户上下文，由租户拦截器与 RLS 隔离。 */
public final class ImportBackups {
    private final ImportBackupMapper mapper;
    private final JsonMapper json = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    ImportBackups(ImportBackupMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 分页读取任务快照供服务内修复流程使用；其他租户与不存在的任务都返回空列表。
     * 业务服务须在公开数据修复接口校验权限，不得把快照中的字段原文直接返回给浏览器。
     */
    public List<ImportBackup> list(UUID taskId, int size, long offset) {
        if (size < 1 || size > 1000 || offset < 0) {
            throw new IllegalArgumentException("Invalid import backup pagination");
        }
        return mapper.list(taskId, size, offset).stream()
                .map(row -> new ImportBackup(
                        row.id(),
                        row.taskId(),
                        row.recordId(),
                        json.readValue(row.valuesJson(), new TypeReference<Map<String, Object>>() {}),
                        row.importedVersion(),
                        row.expiresAt()))
                .toList();
    }

    void capture(UUID taskId, ImportSnapshot snapshot, OffsetDateTime expiresAt) {
        mapper.capture(
                UuidV7.generate(), taskId, snapshot.recordId(), json.writeValueAsString(snapshot.values()), expiresAt);
    }

    void complete(UUID taskId, ImportSnapshot snapshot, String version) {
        Objects.requireNonNull(version, "Overwritten rows need a post-import modification marker");
        mapper.complete(taskId, snapshot.recordId(), version);
    }

    void refreshTask(UUID taskId) {
        mapper.refreshTask(taskId);
    }
}
