package io.saasforge.iam.audit.model;

import java.time.OffsetDateTime;

/**
 * 审计查询条件，各项为 null 时不过滤。
 *
 * @param from 发生时间下限（含）
 * @param to 发生时间上限（不含）
 * @param actor 操作人账号或显示名中包含的文字
 * @param action 动作，精确匹配
 * @param resourceType 对象类型，精确匹配
 * @param resourceId 对象标识，精确匹配
 */
public record AuditQuery(
        OffsetDateTime from, OffsetDateTime to, String actor, String action, String resourceType, String resourceId) {}
