package io.saasforge.iam.announcement.model;

import java.time.Instant;
import java.util.UUID;

/** 平台管理公告的持久化查询结果，目标列表单独读取。 */
public record PlatformAnnouncementRow(
        UUID id,
        String title,
        String body,
        String subApp,
        String route,
        Instant expiresAt,
        Instant publishedAt,
        boolean revoked,
        boolean allTenants) {}
