package io.saasforge.iam.audit.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 审计表中的一条记录及操作人的当前账号与显示名，供查询接口转换。
 *
 * @param actorAccount 操作人账号；操作人未知或已删除时为 null
 * @param actorName 操作人显示名；操作人未知或已删除时为 null
 * @param detail 补充信息的 JSON 文本
 */
public record AuditRecord(
        UUID id,
        OffsetDateTime occurredAt,
        String category,
        String action,
        UUID actorId,
        String actorAccount,
        String actorName,
        String resourceType,
        String resourceId,
        String outcome,
        String errorCode,
        String detail,
        String service,
        String traceId) {}
