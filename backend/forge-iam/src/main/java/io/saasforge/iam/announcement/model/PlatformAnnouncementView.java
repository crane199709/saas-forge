package io.saasforge.iam.announcement.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 平台公告管理结果，全部租户公告不展开目标租户。 */
public record PlatformAnnouncementView(
        UUID id,
        String source,
        String title,
        String body,
        String subApp,
        String route,
        Instant expiresAt,
        Instant publishedAt,
        boolean revoked,
        boolean allTenants,
        List<UUID> tenantIds) {
    public static PlatformAnnouncementView from(PlatformAnnouncementRow row, List<UUID> targets) {
        return new PlatformAnnouncementView(
                row.id(),
                "platform",
                row.title(),
                row.body(),
                row.subApp(),
                row.route(),
                row.expiresAt(),
                row.publishedAt(),
                row.revoked(),
                row.allTenants(),
                targets);
    }
}
