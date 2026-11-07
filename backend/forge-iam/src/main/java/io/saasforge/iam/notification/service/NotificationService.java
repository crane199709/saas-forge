package io.saasforge.iam.notification.service;

import io.saasforge.iam.membership.model.Member;
import io.saasforge.iam.membership.model.TenantActor;
import io.saasforge.iam.membership.service.MembershipService;
import io.saasforge.iam.notification.mapper.NotificationMapper;
import io.saasforge.iam.notification.model.NotificationResponse;
import io.saasforge.iam.role.service.RoleService;
import io.saasforge.sdk.core.api.ForgeException;
import io.saasforge.sdk.core.api.FrameworkErrorCode;
import io.saasforge.sdk.notification.NotificationSent;
import io.saasforge.sdk.provisioning.TenantDataCleaner;
import io.saasforge.sdk.provisioning.TenantDeleted;
import io.saasforge.sdk.scheduling.GlobalTask;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationService implements TenantDataCleaner {
    private final NotificationMapper notifications;
    private final MembershipService memberships;
    private final RoleService roles;
    private final Clock clock;

    NotificationService(
            NotificationMapper notifications,
            MembershipService memberships,
            RoleService roles,
            ObjectProvider<Clock> clocks) {
        this.notifications = notifications;
        this.memberships = memberships;
        this.roles = roles;
        this.clock = clocks.getIfAvailable(Clock::systemUTC);
    }

    @Transactional
    public void store(NotificationSent event) {
        LocalDate month =
                event.occurredAt().atOffset(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1);
        // 已整月过期的迟到或重投消息不能把清理过的分区重新建回来。
        if (month.isBefore(LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
                .minusDays(180)
                .withDayOfMonth(1))) return;
        notifications.bindTenant(event.tenantId());
        notifications.ensurePartition(month);
        if (notifications.insert(event) == 0) return;
        if (event.target().permissionCode() != null) {
            long offset = 0;
            List<Member> page;
            do {
                page = memberships.page(event.tenantId(), offset, 500);
                for (Member member : page) {
                    if (roles.grantsOf(member.membershipId())
                            .permissions()
                            .contains(event.target().permissionCode())) {
                        notifications.addRecipient(event, member.membershipId());
                    }
                }
                offset += page.size();
            } while (page.size() == 500);
            return;
        }
        for (var id : event.target().membershipIds()) {
            if (memberships.exists(event.tenantId(), id)) notifications.addRecipient(event, id);
        }
    }

    @Transactional(readOnly = true)
    public List<NotificationResponse> page(TenantActor actor, long offset, int limit) {
        requireActive(actor);
        return notifications.page(actor.tenantId(), actor.membershipId(), offset, limit);
    }

    @Transactional(readOnly = true)
    public long count(TenantActor actor) {
        requireActive(actor);
        return notifications.count(actor.tenantId(), actor.membershipId());
    }

    @Transactional(readOnly = true)
    public long unreadCount(TenantActor actor) {
        requireActive(actor);
        return notifications.unreadCount(actor.tenantId(), actor.membershipId());
    }

    @Transactional
    public void markRead(TenantActor actor, UUID id) {
        requireActive(actor);
        if (id != null && !notifications.exists(actor.tenantId(), actor.membershipId(), id))
            throw new ForgeException(NotificationErrorCode.NOT_FOUND);
        notifications.markRead(actor.tenantId(), actor.membershipId(), id);
    }

    private void requireActive(TenantActor actor) {
        if (memberships
                .status(actor.tenantId(), actor.membershipId())
                .filter("active"::equals)
                .isEmpty()) throw new ForgeException(FrameworkErrorCode.AUTH_FORBIDDEN);
        notifications.bindTenant(actor.tenantId());
    }

    @Override
    @Transactional
    public void clean(TenantDeleted event) {
        notifications.bindTenant(event.tenantId());
        notifications.deleteRecipients(event.tenantId());
        notifications.deleteNotifications(event.tenantId());
    }

    @GlobalTask(name = "notification-partitions", fixedDelay = "1h")
    @Transactional
    public void maintainPartitions() {
        notifications.maintainPartitions(LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC));
    }
}
