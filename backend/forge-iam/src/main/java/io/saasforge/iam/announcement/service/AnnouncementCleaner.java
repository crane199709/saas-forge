package io.saasforge.iam.announcement.service;

import io.saasforge.iam.announcement.mapper.AnnouncementMapper;
import io.saasforge.sdk.provisioning.TenantDataCleaner;
import io.saasforge.sdk.provisioning.TenantDeleted;
import org.springframework.stereotype.Component;

/** 清理租户公告与已读状态，平台公告本身仍属于其他目标和未来的新租户。 */
@Component
class AnnouncementCleaner implements TenantDataCleaner {
    private final AnnouncementMapper announcements;

    AnnouncementCleaner(AnnouncementMapper announcements) {
        this.announcements = announcements;
    }

    @Override
    public void clean(TenantDeleted event) {
        announcements.bindTenant(event.tenantId());
        announcements.deleteTenantAnnouncements(event.tenantId());
        announcements.deleteTenantReads(event.tenantId());
        announcements.deleteTenantTargets(event.tenantId());
    }
}
