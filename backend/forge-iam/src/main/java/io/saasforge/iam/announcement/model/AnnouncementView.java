package io.saasforge.iam.announcement.model;

import java.time.Instant;
import java.util.UUID;

/** 当前可见公告或管理列表中的公告；正文只作为纯文本显示。 */
public record AnnouncementView(
        UUID id,
        String source,
        String title,
        String body,
        String subApp,
        String route,
        Instant expiresAt,
        Instant publishedAt,
        boolean revoked,
        boolean read) {}
