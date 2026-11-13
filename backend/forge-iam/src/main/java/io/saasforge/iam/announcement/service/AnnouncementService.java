package io.saasforge.iam.announcement.service;

import io.saasforge.iam.announcement.mapper.AnnouncementMapper;
import io.saasforge.iam.announcement.model.AnnouncementResponse;
import io.saasforge.iam.announcement.model.PlatformAnnouncementResponse;
import io.saasforge.iam.membership.model.TenantActor;
import io.saasforge.iam.membership.service.MembershipService;
import io.saasforge.iam.permission.service.IamPermissions;
import io.saasforge.iam.permission.service.PlatformPermissions;
import io.saasforge.iam.platformrole.service.PlatformRoleService;
import io.saasforge.iam.role.service.RoleService;
import io.saasforge.iam.tenant.service.TenantService;
import io.saasforge.sdk.audit.AuditCategory;
import io.saasforge.sdk.audit.AuditEvent;
import io.saasforge.sdk.audit.AuditRealm;
import io.saasforge.sdk.audit.AuditRecorder;
import io.saasforge.sdk.core.UuidV7;
import io.saasforge.sdk.core.api.ForgeException;
import io.saasforge.sdk.core.api.FrameworkErrorCode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 公告内容和 Membership 已读状态；租户作用域始终来自已验签的 Token。 */
@Service
@Transactional
public class AnnouncementService {
    private final AnnouncementMapper announcements;
    private final MembershipService memberships;
    private final RoleService roles;
    private final AuditRecorder audits;
    private final PlatformRoleService platformRoles;
    private final TenantService tenants;

    AnnouncementService(
            AnnouncementMapper announcements,
            MembershipService memberships,
            RoleService roles,
            AuditRecorder audits,
            PlatformRoleService platformRoles,
            TenantService tenants) {
        this.announcements = announcements;
        this.memberships = memberships;
        this.roles = roles;
        this.audits = audits;
        this.platformRoles = platformRoles;
        this.tenants = tenants;
    }
    /** 发布到当前租户，并审计发布动作。 */
    public AnnouncementResponse publish(
            TenantActor actor, String title, String body, String subApp, String route, Instant expiresAt) {
        bind(actor);
        roles.requirePermission(actor.membershipId(), IamPermissions.ANNOUNCEMENT_MANAGE);
        UUID id = UuidV7.generate();
        announcements.publishTenant(id, actor.tenantId(), actor.identityId(), title, body, subApp, route, expiresAt);
        audits.record(AuditEvent.builder(AuditRealm.TENANT, AuditCategory.OPERATION, "announcement.publish")
                .tenant(actor.tenantId())
                .actor(actor.identityId())
                .resource("announcement", id)
                .build());
        return tenantList(actor).stream()
                .filter(a -> a.id().equals(id))
                .findFirst()
                .orElseThrow();
    }
    /** 当前有效 Membership 可见的公告，包含该 Membership 的已读状态。 */
    public List<AnnouncementResponse> visible(TenantActor actor) {
        bind(actor);
        return announcements.visible(actor.tenantId(), actor.membershipId());
    }
    /** 可见且尚未读的公告数。 */
    public long unread(TenantActor actor) {
        return visible(actor).stream().filter(a -> !a.read()).count();
    }
    /** 只有当前可见的公告可标已读，避免通过标识探测其他租户。 */
    public void read(TenantActor actor, UUID id) {
        if (visible(actor).stream().noneMatch(a -> a.id().equals(id))) {
            throw new ForgeException(AnnouncementErrorCode.NOT_FOUND);
        }
        announcements.markRead(UuidV7.generate(), actor.tenantId(), actor.membershipId(), actor.identityId(), id);
    }

    /** 发布面向全部或指定租户的平台公告，平台权限不复用租户权限。 */
    public PlatformAnnouncementResponse publishPlatform(
            UUID actor,
            String title,
            String body,
            String subApp,
            String route,
            Instant expiresAt,
            boolean allTenants,
            List<UUID> tenantIds) {
        platformRoles.requirePermission(actor, PlatformPermissions.ANNOUNCEMENT_MANAGE);
        List<UUID> targets = tenantIds.stream().distinct().toList();
        if (allTenants != targets.isEmpty() || targets.stream().anyMatch(id -> tenants.name(id) == null)) {
            throw new ForgeException(AnnouncementErrorCode.INVALID_TARGET);
        }
        UUID id = UuidV7.generate();
        announcements.publishPlatform(id, actor, title, body, subApp, route, expiresAt, allTenants);
        targets.forEach(tenant -> announcements.target(id, tenant));
        audits.record(AuditEvent.builder(AuditRealm.PLATFORM, AuditCategory.OPERATION, "announcement.publish")
                .actor(actor)
                .resource("announcement", id)
                .detail("allTenants", allTenants)
                .detail("tenantIds", targets)
                .build());
        return platformList(actor).stream()
                .filter(a -> a.id().equals(id))
                .findFirst()
                .orElseThrow();
    }
    /** 平台管理列表包含已到期和已撤回的公告，供运营核对历史发布。 */
    public List<PlatformAnnouncementResponse> platformList(UUID actor) {
        platformRoles.requirePermission(actor, PlatformPermissions.ANNOUNCEMENT_MANAGE);
        return announcements.platformList().stream()
                .map(a -> PlatformAnnouncementResponse.from(a, announcements.targets(a.id())))
                .toList();
    }

    /** 读取单个当前可见公告，过期和撤回的内容不再对成员提供。 */
    public AnnouncementResponse get(TenantActor actor, UUID id) {
        return visible(actor).stream()
                .filter(a -> a.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new ForgeException(AnnouncementErrorCode.NOT_FOUND));
    }
    /** 当前租户自行发布的公告管理列表，不能管理平台公告。 */
    public List<AnnouncementResponse> tenantList(TenantActor actor) {
        bind(actor);
        roles.requirePermission(actor.membershipId(), IamPermissions.ANNOUNCEMENT_MANAGE);
        return announcements.tenantList(actor.tenantId());
    }
    /** 撤回租户公告，重复撤回不产出重复审计。 */
    public void revoke(TenantActor actor, UUID id) {
        if (tenantList(actor).stream().noneMatch(a -> a.id().equals(id))) {
            throw new ForgeException(AnnouncementErrorCode.NOT_FOUND);
        }
        if (announcements.revokeTenant(actor.tenantId(), id, actor.identityId()) > 0) {
            audits.record(AuditEvent.builder(AuditRealm.TENANT, AuditCategory.OPERATION, "announcement.revoke")
                    .tenant(actor.tenantId())
                    .actor(actor.identityId())
                    .resource("announcement", id)
                    .build());
        }
    }
    /** 撤回平台公告，只要求 Platform Realm 的公告管理权限。 */
    public void revokePlatform(UUID actor, UUID id) {
        if (platformList(actor).stream().noneMatch(a -> a.id().equals(id))) {
            throw new ForgeException(AnnouncementErrorCode.NOT_FOUND);
        }
        if (announcements.revokePlatform(id, actor) > 0) {
            audits.record(AuditEvent.builder(AuditRealm.PLATFORM, AuditCategory.OPERATION, "announcement.revoke")
                    .actor(actor)
                    .resource("announcement", id)
                    .build());
        }
    }

    private void bind(TenantActor actor) {
        if (!memberships
                .status(actor.tenantId(), actor.membershipId())
                .filter("active"::equals)
                .isPresent()) {
            throw new ForgeException(FrameworkErrorCode.AUTH_UNAUTHENTICATED);
        }
        announcements.bindTenant(actor.tenantId());
    }
}
